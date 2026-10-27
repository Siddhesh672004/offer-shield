package com.offershield.support;

import org.xbill.DNS.DClass;
import org.xbill.DNS.EDNSOption;
import org.xbill.DNS.Flags;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * An in-memory dnsjava {@link Resolver}: answers MX and TXT questions from what a test registered, NXDOMAIN for any
 * other name, and records every question, so no test ever sends a real DNS query.
 */
public final class FakeDnsResolver implements Resolver {

    private final Map<String, List<String>> mx = new ConcurrentHashMap<>();
    private final Map<String, List<String>> txt = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Set<String> hanging = ConcurrentHashMap.newKeySet();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    /** MX targets for {@code name}; "." is a null MX (RFC 7505). */
    public FakeDnsResolver mx(String name, String... targets) {
        mx.put(key(name), List.of(targets));
        return this;
    }

    public FakeDnsResolver txt(String name, String... texts) {
        txt.put(key(name), List.of(texts));
        return this;
    }

    /** Answer questions about {@code name} with {@code rcode} (e.g. {@link Rcode#SERVFAIL}). */
    public FakeDnsResolver fail(String name, int rcode) {
        failures.put(key(name), rcode);
        return this;
    }

    /** Never answer questions about {@code name}. */
    public FakeDnsResolver hang(String name) {
        hanging.add(key(name));
        return this;
    }

    /** Every question asked, as "name TYPE" (name without the trailing dot). */
    public List<String> queries() {
        return List.copyOf(queries);
    }

    @Override
    public CompletionStage<Message> sendAsync(Message query) {
        Record question = query.getQuestion();
        String name = key(question.getName().toString());
        queries.add(name + " " + Type.string(question.getType()));
        if (hanging.contains(name)) {
            return new CompletableFuture<>();
        }
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.addRecord(question, Section.QUESTION);
        Integer failure = failures.get(name);
        if (failure != null) {
            response.getHeader().setRcode(failure);
            return CompletableFuture.completedFuture(response);
        }
        if (!mx.containsKey(name) && !txt.containsKey(name)) {
            response.getHeader().setRcode(Rcode.NXDOMAIN);
            return CompletableFuture.completedFuture(response);
        }
        response.getHeader().setRcode(Rcode.NOERROR);
        try {
            Name owner = Name.fromString(name, Name.root);
            if (question.getType() == Type.MX) {
                for (String target : mx.getOrDefault(name, List.of())) {
                    Name targetName = ".".equals(target) ? Name.root : Name.fromString(target, Name.root);
                    response.addRecord(new MXRecord(owner, DClass.IN, 300, 10, targetName), Section.ANSWER);
                }
            } else if (question.getType() == Type.TXT) {
                for (String text : txt.getOrDefault(name, List.of())) {
                    response.addRecord(new TXTRecord(owner, DClass.IN, 300, text), Section.ANSWER);
                }
            }
        } catch (TextParseException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletionStage<Message> sendAsync(Message query, Executor executor) {
        return sendAsync(query);
    }

    @Override
    public void setPort(int port) {
    }

    @Override
    public void setTCP(boolean flag) {
    }

    @Override
    public void setIgnoreTruncation(boolean flag) {
    }

    @Override
    public void setEDNS(int version, int payloadSize, int flags, List<EDNSOption> options) {
    }

    @Override
    public void setTSIGKey(TSIG key) {
    }

    @Override
    public void setTimeout(Duration timeout) {
    }

    private static String key(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }
}
