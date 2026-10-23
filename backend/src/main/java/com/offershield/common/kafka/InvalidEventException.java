package com.offershield.common.kafka;

/**
 * A record that was valid JSON but not a usable event (missing IDs, unknown outcome). Retrying cannot fix it, so the
 * error handler sends it straight to {@code <topic>.DLT} (D-28). The message names the problem, never record content.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String reason) {
        super(reason);
    }
}
