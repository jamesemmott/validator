/*
 * Copyright (c) 2005 Henri Sivonen
 * Copyright (c) 2007-2019 Mozilla Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package nu.validator.servlet;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.EnumSet;
import java.util.Enumeration;

import javax.servlet.DispatcherType;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.PropertyConfigurator;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * @version $Id$
 * @author hsivonen
 */
public class Main {

    private static final String BIND_ADDRESS = System.getProperty(
            "nu.validator.servlet.bind-address", "0.0.0.0");

    private static final long SIZE_LIMIT = Integer.parseInt(System.getProperty(
            "nu.validator.servlet.max-file-size", "2097152"));

    private static final void emitInterfaceInformation(Logger log4j) {
        String ip;
        try {
            Enumeration<NetworkInterface> interfaces = //
                NetworkInterface.getNetworkInterfaces();
            log4j.debug("");
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp())
                    continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    ip = addr.getHostAddress();
                    log4j.debug(String.format("%10s %s", //
                                iface.getDisplayName(), ip)); }
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
    }

    private static final void emitStartupMessage(Logger log4j, String host,
            int port) {
        log4j.debug("");
        log4j.debug(String.format("Checker service started at http://%s:%s/",
                host, port));
    }

    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getProperty(
                "nu.validator.servlet.read-local-log4j-properties"))) {
            PropertyConfigurator.configure(
                    Main.class.getClassLoader().getResource(
                            "nu/validator/localentities/files/log4j.properties"));
        } else {
            PropertyConfigurator.configure(
                    System.getProperty("nu.validator.servlet.log4j-properties",
                            "log4j.properties"));
        }
        Logger log4j = Logger.getLogger(Main.class);
        ConsoleAppender console = new ConsoleAppender();
        console.setLayout(new PatternLayout("%m%n"));
        console.activateOptions();
        log4j.setAdditivity(false);
        log4j.addAppender(console);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setIncludedMethods("POST", "GET");
        gzipHandler.setInflateBufferSize(2048);
        gzipHandler.setHandler(contextHandler);
        contextHandler.addFilter(new FilterHolder(new InboundSizeLimitFilter(
                SIZE_LIMIT)), "/*", EnumSet.of(DispatcherType.REQUEST));
        contextHandler.addFilter(new FilterHolder(new InboundGzipFilter()),
                "/*", EnumSet.of(DispatcherType.REQUEST));
        contextHandler.addFilter(
                new FilterHolder(new MultipartFormDataFilter()), "/*",
                EnumSet.of(DispatcherType.REQUEST));
        contextHandler.addServlet(new ServletHolder(new VerifierServlet()),
                "/*");

        Server server = new Server(new QueuedThreadPool(100));
        server.setHandler(contextHandler);
        server.setHandler(gzipHandler);

        ServerConnector serverConnector = new ServerConnector(server,
                new HttpConnectionFactory(new HttpConfiguration()),
                new HTTP2CServerConnectionFactory(new HttpConfiguration()));
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8888;
        serverConnector.setPort(port);
        serverConnector.setHost(BIND_ADDRESS);
        server.setConnectors(new Connector[] { serverConnector });

        int stopPort = -1;
        if (args.length > 1) {
            stopPort = Integer.parseInt(args[1]);
        }
        if (stopPort != -1) {
            try (Socket clientSocket = new Socket(
                    InetAddress.getByName("127.0.0.1"), stopPort);
                    InputStream in = clientSocket.getInputStream()) {
                in.read();
            } catch (ConnectException e) {

            }

            server.start();
            emitInterfaceInformation(log4j);
            emitStartupMessage(log4j, serverConnector.getHost(), port);

            try (ServerSocket serverSocket = new ServerSocket(stopPort, 0,
                    InetAddress.getByName("127.0.0.1"));
                    Socket s = serverSocket.accept()) {
                server.stop();

                s.getOutputStream().close();
            }
        } else {
            server.start();
            emitInterfaceInformation(log4j);
            emitStartupMessage(log4j, serverConnector.getHost(), port);
        }
    }
}
