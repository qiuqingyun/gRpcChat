package org.gRpcChat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GRpcModule {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());

    public static void main(String[] args) throws InterruptedException {
        Options options = new Options();
        //服务器模式
        options.addOption(Option.builder("s").longOpt("server").desc("Server mode").build());
        //用户名
        options.addOption(Option.builder("n").longOpt("name").hasArg().desc("User name").build());
        //服务监听端口
        options.addOption(Option.builder("p").longOpt("port").hasArg().desc("Service listening port").build());
        //服务器连接端口
        options.addOption(Option.builder("c").longOpt("connect").hasArg().desc("Server connect port").build());
        //帮助信息
        options.addOption(Option.builder("h").longOpt("help").desc("Print this help message").build());

        //解析器
        CommandLineParser parser = new DefaultParser();
        // 格式器
        HelpFormatter formatter = new HelpFormatter();
        // 解析结果
        CommandLine result = null;

        //解析阶段
        try {
            result = parser.parse(options, args);
        } catch (ParseException e) {
            // 打印帮助信息
            formatter.printHelp("GRpcModule", options, true);
            // 打印解析异常
            System.err.println(e.getMessage());
            // 退出程序，退出码为 1
            System.exit(0);
        }

        //询问阶段
        if (args.length == 0 || result.hasOption("h")) {
            // 打印帮助信息
            formatter.printHelp("GRpcModule", options, true);
            // 退出程序
            System.exit(0);
        }
        //设置服务监听端口
        Random random = new Random();
        int portListening = 40000 + random.nextInt(9999) + 1;
        if (result.hasOption("p")) {
            portListening = Integer.parseInt(result.getOptionValue("p"));
        }
        int finalPortListening = portListening;
        //是否为(转发)服务器模式
        if (result.hasOption("s")) {
            logger.info("Running in server mode");
            //启动Server线程
            Thread threadServer = new Thread(() -> {
                final GRpcServer server = new GRpcServer(finalPortListening);
                try {
                    server.start();
                    server.blockUntilShutdown();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
            threadServer.start();
            threadServer.join();
        } else {
            logger.info("Running in client mode");
            //Client线程
            String name = "Anonymous";
            if (result.hasOption("n")) {
                name = result.getOptionValue("n");
            }
            int portConnect = 50000;
            if (result.hasOption("c")) {
                portConnect = Integer.parseInt(result.getOptionValue("c"));
            }
            String finalName = name;
            int finalPortConnect = portConnect;
            Thread threadClient = new Thread(() -> {
                String target = "localhost:" + finalPortConnect;
                // 与服务器连接的通道（明文）
                ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
                GRpcClient client = new GRpcClient(channel, finalName);

                //登录
                try {
                    CountDownLatch finishLatch = client.login();
                    if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                        logger.warn("Login can not finish within 1 minutes");
                    }
                } catch (InterruptedException e) {
                    logger.warn("Login failed.");
                    e.printStackTrace();
                }
                Scanner scanner = new Scanner(System.in);
                //循环，直到输入#logout
                while (true) {
                    //发送消息
                    System.out.print("\rSend(#act/Name@Message): ");
                    String inputStr = scanner.nextLine();
                    if (inputStr.contains("#") && inputStr.contains("/") && inputStr.contains("@")) {//发送信息模式
                        try {
                            CountDownLatch finishLatch = client.post(inputStr.split("/")[0], inputStr.split("/")[1].split("@")[0], inputStr.split("/")[1].split("@")[1]);
                            if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                                logger.warn("Post message can not finish within 1 minutes");
                            }
                        } catch (InterruptedException e) {
                            logger.warn("Post message failed.");
                            e.printStackTrace();
                        }
                    } else if (inputStr.compareTo("#logout") == 0) {
                        try {
                            CountDownLatch finishLatch = client.logout();
                            if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                                logger.warn("Logout can not finish within 1 minutes");
                            }
                        } catch (InterruptedException e) {
                            logger.warn("Logout failed.");
                            e.printStackTrace();
                        }
                        break;
                    } else {
                        System.out.println("Format error");
                    }
                }
                try {
                    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.exit(0);
            });
            threadClient.start();
            threadClient.join();
        }
        System.exit(0);
    }
}
