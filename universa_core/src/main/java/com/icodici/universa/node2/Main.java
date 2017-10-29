/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node2;

import com.icodici.crypto.PrivateKey;
import com.icodici.crypto.PublicKey;
import com.icodici.universa.node2.network.ClientHTTPServer;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.sergeych.tools.*;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;

import static java.util.Arrays.asList;

public class Main {
    private  final String NODE_VERSION = "2.0.6";
    private  OptionParser parser;
    private  OptionSet options;
    public  final Reporter reporter = new Reporter();

    private  String NAME_STRING = "Universa node server v" + NODE_VERSION + "\n";

    private  AsyncEvent eventReady = new AsyncEvent();
    public  final BufferedLogger logger = new BufferedLogger(4096);
    private  String configRoot;


     public static void main(String[] args) {
         new Main(args);
     }

     Main(String[] args) {
        parser = new OptionParser() {
            {
                acceptsAll(asList("?", "h", "help"), "show help").forHelp();
                acceptsAll(asList("c", "config"), "configuration file for the network")
                        .withRequiredArg().ofType(String.class).defaultsTo(".")
                        .required()
                        .describedAs("config_file");
                accepts("test", "intended to be used in integration tests");
                accepts("nolog", "do not buffer log messages (good fot testing)");
            }
        };
        try {
            options = parser.parse(args);
//            if (options.has("nolog"))
//                logger.interceptStdOut();
//            else
//                logger.printTo(System.out, false);
                logger.printTo(System.out, false);
            if (options.has("?")) {
                usage(null);
            }
            log(NAME_STRING);
            log("Starting client interface");
            loadNodeConfig();

            System.out.println("--------------- step 2 --------------------");
            log("loading network configuration...");
            loadNetConfig();

            System.out.println("--------------- step 3 --------------------");
            log("Starting the client HTTP server...");
            startClientHttpServer();

            System.out.println("all initialization is done -----------------------------------");
            startAndWaitEnd();
        } catch (OptionException e) {
            usage("Unrecognized parameter: " + e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            System.out.println("interrupted exception, leaving");
            System.err.println("interrupted exception, leaving");
        } catch (Exception e) {
            System.out.println("exception "+e);
            System.err.println("exception "+e);
            e.printStackTrace();
            usage(e.getMessage());
        }
    }

    public  NetConfig netConfig;
    private  void loadNetConfig() throws IOException {
        netConfig = new NetConfig(configRoot+"/config/nodes");
        log("Network configuration is loaded from "+configRoot+", "+netConfig.size() + " nodes.");
    }

    /**
     * To use in unti-tests. Start a node and blocks the thread until the nodes stops.
     *
     * @throws InterruptedException
     */
    private  void startAndWaitEnd() throws InterruptedException {
        eventReady.fire(null);
        if (!options.has("test"))
            synchronized (parser) {
                parser.wait();
            }
    }

    /**
     * For unit-tests. Blocks until the node is initialized.
     *
     * @throws InterruptedException
     */
    public  void waitReady() throws InterruptedException {
        eventReady.await();
    }

    /**
     * For unit-tests. REquest the shutdown and wait until the node stops.
     */
     public void shutdown() {
        try {
//            network.close();
            log("shutting down");
        } catch (Exception e) {
        }

        synchronized (parser) {
            parser.notifyAll();
        }
        try {
            logger.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

     private PrivateKey nodeKey;
     private Binder settings;

     public PublicKey getNodePublicKey() {
        return nodeKey.getPublicKey();
    }

     public NodeInfo myInfo;

    private  void loadNodeConfig() throws IOException {
        Yaml yaml = new Yaml();
        configRoot = (String) options.valueOf("config");

        nodeKey = null;
        settings = Binder.of(yaml.load(new FileInputStream(configRoot + "/config/config.yaml")));
        log("node settings: " + settings);
        String nodeKeyFileName = configRoot + "/tmp/" + settings.getStringOrThrow("node_name") + ".private.unikey";
        log(nodeKeyFileName);
        nodeKey = new PrivateKey(Do.read(nodeKeyFileName));

        myInfo = new NodeInfo(nodeKey.getPublicKey(),
                              settings.getIntOrThrow("node_number"),
                              settings.getStringOrThrow("node_name"),
                              (String) settings.getListOrThrow("ip").get(0),
                              settings.getStringOrThrow("public_host"),
                              settings.getIntOrThrow("udp_server_port"),
                              settings.getIntOrThrow("http_client_port"),
                              settings.getIntOrThrow("http_server_port")
        );

        log("key loaded: " + nodeKey.info());
        log( "node local URL: "+ myInfo.publicUrlString());
        log( "node info: "+ myInfo.toBinder());
    }

    private  ClientHTTPServer clientHTTPServer;

    public  Node node;
    public  final Config config = new Config();
    public  ItemCache cache = new ItemCache(Duration.ofMinutes(30));

    private  void setupNode() {
        config.setNegativeConsensus(1);
        config.setPositiveConsensus(1);
//        node = new Node(config, )
    }

    private  void startClientHttpServer() throws Exception {
        System.out.println("prepare to start client HTTP server on "+settings.getIntOrThrow("http_client_port"));
        clientHTTPServer = new ClientHTTPServer(nodeKey, settings.getIntOrThrow("http_client_port"), logger);
        clientHTTPServer.setCache(cache);
        clientHTTPServer.setNetConfig(netConfig);
//        node = new Node()
    }

    private  void log(String msg) {
        System.out.println(msg);
//        logger.log(msg);
    }

     private void usage(String text) {
        System.out.println("usafe called");
        boolean error = false;
        PrintStream out = System.out;
        if (text != null) {
            out = System.err;
            error = true;
        }
        out.println("\n" + NAME_STRING);
        if (text != null)
            out.println("ERROR: " + text + "\n");
        try {
            parser.printHelpOn(out);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (options != null && !options.has("test"))
            System.exit(100);
    }
}