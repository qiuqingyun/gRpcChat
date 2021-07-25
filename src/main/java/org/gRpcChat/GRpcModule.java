package org.gRpcChat;

import com.google.crypto.tink.*;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.config.TinkConfig;
import com.google.crypto.tink.hybrid.HybridConfig;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GRpcModule {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());

    public static void main(String[] args) throws InterruptedException {
        Options options = new Options();
        //服务器模式
        options.addOption(Option.builder("s").longOpt("server").desc("Server mode [default: closed]").build());
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
        int portListening = 50000;
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
            //确定用户名
            String name = GRpcUtil.getRandomString(6);
            if (result.hasOption("n")) {
                name = result.getOptionValue("n");
            }
            System.out.println("Account name: " + name);
            logger.info("Account name: " + name);
            //确定密钥
            KeysetHandle sk = null, pk = null;
            try {
                HybridConfig.register();
            } catch (GeneralSecurityException e) {
                System.out.println("KeyGen Initialization Failed");
                e.printStackTrace();
                System.exit(1);
            }
            if (result.hasOption("k")) {
                try {//导入密钥
                    sk = CleartextKeysetHandle.read(BinaryKeysetReader.withFile(new File(result.getOptionValue("k"))));//私钥
                    pk = sk.getPublicKeysetHandle();//公钥
                } catch (GeneralSecurityException e) {
                    System.out.println("General Key \"" + result.getOptionValue("k") + "\" Failed");
                    System.exit(1);
                } catch (IOException e) {
                    System.out.println("Key File \"" + result.getOptionValue("k") + "\" Not Found");
                    System.exit(1);
                }
            } else {
                //生成随机密钥
                try {
                    sk = KeysetHandle.generateNew(KeyTemplates.get("ECIES_P256_COMPRESSED_HKDF_HMAC_SHA256_AES128_GCM"));//私钥
                    pk = sk.getPublicKeysetHandle();//公钥
                } catch (GeneralSecurityException e) {
                    e.printStackTrace();
                    logger.error("KeyGen Error");
                    System.exit(1);
                }
                //保存密钥
                try {
                    CleartextKeysetHandle.write(sk, BinaryKeysetWriter.withFile(new File(name + ".key")));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                logger.info("Key File \"" + name + ".key\" Saved");
            }
            //确定服务器ip
            String ipConnect = "127.0.0.1";
            if (result.hasOption("i")) {
                ipConnect = result.getOptionValue("i");
            }
            //确定服务器rpc端口
            int portConnect = 50000;
            if (result.hasOption("p")) {
                portConnect = Integer.parseInt(result.getOptionValue("p"));
            }
            //创建Client线程
            String finalName = name;
            KeysetHandle finalPk = pk;
            KeysetHandle finalSk = sk;
            String finalIpConnect = ipConnect;
            int finalPortConnect = portConnect;
            Thread threadClient = new Thread(() -> {
                String target = finalIpConnect + ":" + finalPortConnect;
                logger.info("Connect to " + target);
                // 与服务器连接的通道
                ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
                GRpcClient client = new GRpcClient(channel);
                client.setAccountInfo(finalName, finalPk, finalSk);
                //登录
                try {
                    CountDownLatch finishLatch = client.login();
                    if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                        logger.warn("Login can not finish within 1 minutes");
                    }
                    if (client.isLoginSuccessful()) {
                        logger.info("Login Successful");
                    } else {
                        logger.info("Login Failed");
                        System.exit(1);
                    }
                } catch (InterruptedException e) {
                    logger.warn("Login failed.");
                    e.printStackTrace();
                }
                logger.info("Receiver: #Everyone");
                //循环，直到输入#logout
                boolean completeFlag = false;
                do {
                    Scanner scanner = new Scanner(System.in);
                    //发送消息
                    if (client.getReceiver() != null)
                        System.out.print("Send to [" + client.getReceiver() + "]: ");
                    else
                        System.out.print("Send to [#Everyone]:");
                    String inputStr = scanner.nextLine();
                    String warnMessage = null;
                    CountDownLatch finishLatch = null;
                    boolean awaitFlag = true;
                    try {
                        //其他功能
                        if ("#function".equals(inputStr)) {
                            GRpcUtil.printFunctions();
                            inputStr = scanner.nextLine();

                            switch (inputStr) {
                                case "logout" -> {//登出
                                    warnMessage = "Logout";
                                    finishLatch = client.logout();
                                    completeFlag = true;
                                }
                                case "userlist" -> {//加载在线用户列表
                                    warnMessage = "Load user list";
                                    awaitFlag = false;
                                    client.showUserList();
                                }
                                case "setreceiver" -> {//设置接收者
                                    warnMessage = "Set target";
                                    awaitFlag = false;
                                    client.setReceiver();
                                }
                                default -> {
                                    System.out.println("Format Error");
                                    awaitFlag = false;
                                }
                            }
                        } else {//发送信息
                            warnMessage = "Post message";
                            finishLatch = client.post(inputStr);
                        }
                        //等待同步
                        if (awaitFlag && (!finishLatch.await(1, TimeUnit.MINUTES))) {
                            logger.warn(warnMessage + " can not finish within 1 minutes");
                        }
                    } catch (InterruptedException e) {
                        logger.warn(warnMessage + " failed.");
                        e.printStackTrace();
                    }
                } while (!completeFlag);

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
