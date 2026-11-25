/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.servlets;

import java.io.File;
import java.io.FileWriter;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.compat.JrePlatform;

/*
 * The CGI script used by these tests echoes its standard input back to the
 * client and only exits once it sees EOF on standard input. If the CGI Servlet
 * does not pass the request body (or the lack of one) to the script and close
 * the script's standard input, the script, the CGI process and the request
 * processing thread hang indefinitely.
 */
public class TestCGIServlet extends TomcatBaseTest {

    private static final String BODY = "Hello World";

    @Test
    public void testPostBodyChunked() throws Exception {
        // @formatter:off
        String[] request = new String[] {
            "POST /cgi/echo.sh HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: localhost" + SimpleHttpClient.CRLF +
            "Content-Type: text/plain" + SimpleHttpClient.CRLF +
            "Transfer-Encoding: chunked" + SimpleHttpClient.CRLF +
            "Connection: close" + SimpleHttpClient.CRLF +
            SimpleHttpClient.CRLF +
            Integer.toHexString(BODY.length()) + SimpleHttpClient.CRLF +
            BODY + SimpleHttpClient.CRLF +
            "0" + SimpleHttpClient.CRLF +
            SimpleHttpClient.CRLF
        };
        // @formatter:on
        doTestCGIRequest(request, true);
    }


    @Test
    public void testPostBodyContentLength() throws Exception {
        // @formatter:off
        String[] request = new String[] {
            "POST /cgi/echo.sh HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: localhost" + SimpleHttpClient.CRLF +
            "Content-Type: text/plain" + SimpleHttpClient.CRLF +
            "Content-Length: " + BODY.length() + SimpleHttpClient.CRLF +
            "Connection: close" + SimpleHttpClient.CRLF +
            SimpleHttpClient.CRLF +
            BODY
        };
        // @formatter:on
        doTestCGIRequest(request, true);
    }


    @Test
    public void testGetNoBody() throws Exception {
        // @formatter:off
        String[] request = new String[] {
            "GET /cgi/echo.sh HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: localhost" + SimpleHttpClient.CRLF +
            "Connection: close" + SimpleHttpClient.CRLF +
            SimpleHttpClient.CRLF
        };
        // @formatter:on
        doTestCGIRequest(request, false);
    }


    private void doTestCGIRequest(String[] request, boolean expectBody) throws Exception {
        // The test script requires a POSIX shell
        Assume.assumeFalse(JrePlatform.IS_WINDOWS);

        Tomcat tomcat = getTomcatInstance();

        File appDir = new File(getTemporaryDirectory(), "cgitest");
        Assert.assertTrue(appDir.mkdirs());
        addDeleteOnTearDown(appDir);

        File cgiDir = new File(appDir, "WEB-INF/cgi");
        Assert.assertTrue(cgiDir.mkdirs());

        File echoScript = new File(cgiDir, "echo.sh");
        try (FileWriter fw = new FileWriter(echoScript)) {
            fw.write("#!/bin/sh\n");
            fw.write("echo \"Content-Type: text/plain\"\n");
            fw.write("echo\n");
            fw.write("echo \"Start\"\n");
            // Reads standard input until EOF
            fw.write("cat\n");
            fw.write("echo\n");
            fw.write("echo \"End\"\n");
        }
        Assert.assertTrue(echoScript.setExecutable(true));

        Context ctx = tomcat.addContext("", appDir.getAbsolutePath());
        ctx.setPrivileged(true);

        Wrapper cgi = Tomcat.addServlet(ctx, "cgi", new CGIServlet());
        cgi.addInitParameter("cgiPathPrefix", "WEB-INF/cgi");
        cgi.addInitParameter("executable", "");
        ctx.addServletMapping("/cgi/*", "cgi");

        tomcat.start();

        CGIClient client = new CGIClient();
        client.setPort(getPort());
        client.setRequest(request);
        // Fail rather than hang if the CGI script never sees EOF on stdin
        client.connect(10000, 10000);
        try {
            client.processRequest();
        } finally {
            client.disconnect();
        }

        Assert.assertTrue(client.getResponseLine(), client.isResponse200());
        String responseBody = client.getResponseBody();
        Assert.assertTrue(responseBody, client.isResponseBodyOK());
        Assert.assertEquals(responseBody, Boolean.valueOf(expectBody),
                Boolean.valueOf(responseBody.contains(BODY)));
    }


    private static final class CGIClient extends SimpleHttpClient {

        @Override
        public boolean isResponseBodyOK() {
            String body = getResponseBody();
            return body != null && body.contains("Start") && body.contains("End");
        }
    }
}
