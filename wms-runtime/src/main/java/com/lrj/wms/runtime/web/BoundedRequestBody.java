package com.lrj.wms.runtime.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 覆盖普通和 chunked 请求体，防止声明长度缺失时绕过 JSON 内存预算。 */
public final class BoundedRequestBody extends HttpServletRequestWrapper {
    public static final int MAX_BYTES = 1048576;
    private final ServletInputStream bounded;
    public BoundedRequestBody(HttpServletRequest request) throws IOException {
        super(request);
        var input = request.getInputStream();
        this.bounded = new ServletInputStream() {
            private long read;
            @Override public int read() throws IOException {
                int value = input.read();
                if (value != -1 && ++read > MAX_BYTES) throw new IOException("请求体超过 1 MiB");
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = input.read(bytes, offset, (int) Math.min(length, MAX_BYTES - read + 1));
                if (count > 0 && (read += count) > MAX_BYTES) throw new IOException("请求体超过 1 MiB");
                return count;
            }
            @Override public boolean isFinished() { return input.isFinished(); }
            @Override public boolean isReady() { return input.isReady(); }
            @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
            @Override public void close() throws IOException { input.close(); }
        };
    }
    @Override public ServletInputStream getInputStream() { return bounded; }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8)); }
}
