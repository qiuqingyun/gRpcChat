package org.gRpcChat;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GRpcChat {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());

    public static void main(String[] args) throws InterruptedException {
        Options options = new Options();
        //客户端模式
        options.addOption(Option.builder("c").longOpt("client").desc("Client mode [default]").build());
        //服务器模式
        options.addOption(Option.builder("s").longOpt("server").desc("Server mode").build());
        //服务器地址
        options.addOption(Option.builder("i").longOpt("ip").hasArg().desc("Connect ip [default: 127.0.0.1]").build());
        //RPC端口
        options.addOption(Option.builder("p").longOpt("port").hasArg().desc("Connect port [default: 50000]").build());
        //用户名
        options.addOption(Option.builder("n").longOpt("name").hasArg().desc("Account name [default: Random Generation]").build());
        //密钥
        options.addOption(Option.builder("k").longOpt("key").hasArg().desc("Account key file path [default: Random Generation]").build());
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
            formatter.printHelp("gRpcChat", options, true);
            logger.error("Command Line Parse Error: " + e.getMessage());
            System.exit(1);
        }
        //模式选择
        if (result.hasOption("s")) {//服务端
            ChatServer chatServer = new ChatServer(result);
            chatServer.run();
        } else if ((result.hasOption("c"))) {//客户端
            ChatClient chatClient = new ChatClient(result);
            chatClient.run();
        } else {
            // 打印帮助信息
            formatter.printHelp("gRpcChat", options, true);
        }
        System.exit(0);
    }
}
