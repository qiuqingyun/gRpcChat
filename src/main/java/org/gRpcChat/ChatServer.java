package org.gRpcChat;

import org.apache.commons.cli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ChatServer {
    private final static Logger logger = LoggerFactory.getLogger("Server");
    private int portListening = 50000;

    //初始化
    public ChatServer(CommandLine result) {
        //设置服务监听端口
        if (result.hasOption("p")) {
            portListening = Integer.parseInt(result.getOptionValue("p"));
        }
    }

    //运行服务端线程
    public void run() throws InterruptedException {
        logger.info("Running in server mode");
        Thread threadServer = new Thread(() -> {
            final GRpcServer server = new GRpcServer(portListening);
            try {
                server.start();
                server.blockUntilShutdown();
            } catch (IOException | InterruptedException e) {
                logger.error("Server Error: " + e.getMessage());
                System.exit(1);
            }
        });
        threadServer.start();
        threadServer.join();
    }
}
