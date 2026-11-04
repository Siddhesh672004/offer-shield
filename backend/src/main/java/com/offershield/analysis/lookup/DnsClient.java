package com.offershield.analysis.lookup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xbill.DNS.DClass;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mail setup over DNS (C6, D-19) with dnsjava: does the sender host have an MX record (a null MX "." does not count),
 * and is there a DMARC record ({@code TXT} at {@code _dmarc.<host>} starting {@code v=DMARC1}, else at the
 * registrable domain, RFC 7489)? Accepts only a {@link ValidatedDomain}; asks only the configured resolvers and never
 * connects to the domain itself. One 3 s budget covers all queries of a lookup (D-31); a found answer is cached 1 h,
 * a failure (timeout, SERVFAIL, …) is {@link MailRecords#unavailable()} and not cached.
 */
@Component
public class DnsClient {

    private static final Logger log = LoggerFactory.getLogger(DnsClient.class);

    private final Resolver resolver;
    private final Duration timeout;
    private final Cache<String, MailRecords> cache;

    public DnsClient(Resolver resolver, LookupProperties properties) {
        this.resolver = resolver;
        this.timeout = properties.dns().timeout();
        this.cache = Caffeine.newBuilder().expireAfterWrite(properties.dns().cacheTtl()).maximumSize(10_000).build();
    }

    public MailRecords mailRecords(ValidatedDomain domain) {
        MailRecords cached = cache.getIfPresent(domain.host());
        if (cached != null) {
            return cached;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            boolean hasMx = query(domain.host(), Type.MX, deadline).stream()
                    .map(MXRecord.class::cast)
                    .anyMatch(mx -> !mx.getTarget().equals(Name.root));
            boolean hasDmarc = hasDmarc(domain.host(), deadline)
                    || !domain.host().equals(domain.registrableDomain()) && hasDmarc(domain.registrableDomain(), deadline);
            MailRecords found = MailRecords.found(hasMx, hasDmarc);
            cache.put(domain.host(), found);
            log.info("dns lookup outcome=FOUND");
            return found;
        } catch (LookupFailedException ex) {
            log.info("dns lookup outcome=UNAVAILABLE reason={}", ex.getMessage());
            return MailRecords.unavailable();
        }
    }

    private boolean hasDmarc(String name, long deadline) throws LookupFailedException {
        return query("_dmarc." + name, Type.TXT, deadline).stream()
                .map(TXTRecord.class::cast)
                .map(txt -> String.join("", txt.getStrings()).strip().toUpperCase(Locale.ROOT))
                .anyMatch(text -> text.startsWith("V=DMARC1"));
    }

    /** The answer records of {@code type}; empty for NXDOMAIN or no data; a failure for anything else. */
    private List<Record> query(String name, int type, long deadline) throws LookupFailedException {
        Message question;
        try {
            question = Message.newQuery(Record.newRecord(Name.fromString(name, Name.root), type, DClass.IN));
        } catch (TextParseException ex) {
            throw new LookupFailedException("bad name");
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new LookupFailedException("timeout");
        }
        CompletableFuture<Message> future = resolver.sendAsync(question).toCompletableFuture();
        Message answer;
        try {
            answer = future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new LookupFailedException("timeout");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new LookupFailedException(cause.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LookupFailedException("interrupted");
        }
        int rcode = answer.getRcode();
        if (rcode == Rcode.NXDOMAIN) {
            return List.of();
        }
        if (rcode != Rcode.NOERROR) {
            throw new LookupFailedException("rcode " + Rcode.string(rcode));
        }
        return answer.getSection(Section.ANSWER).stream().filter(record -> record.getType() == type).toList();
    }
}
