package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.io.IOException;

/**
 * Pluggable HTTP transport interface for executing signed S3 requests.
 */
public interface S3HttpTransport {

    /**
     * Executes the given S3 HTTP request synchronously and returns the response.
     *
     * @param request prepared request
     * @return response
     * @throws IOException on network or protocol I/O errors
     * @throws InterruptedException if interrupted during transmission
     */
    S3HttpResponse send(S3HttpRequest request) throws IOException, InterruptedException;
}
