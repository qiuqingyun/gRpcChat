package org.gRpcChat;

import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.hybrid.HybridConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.commons.cli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ChatClient {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());
    private final String connectTarget;
    private final Account account = new Account();
    private boolean completeFlag = false;
    private String warnMessage = null;
    private CountDownLatch finishLatch = null;
    private static boolean keyFileSaveFlag = false;

    //初始化
    public ChatClient(CommandLine result) {
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
        this.connectTarget = ipConnect + ":" + portConnect;
        logger.info(" - SET Connect Target " + this.connectTarget);
        //确定密钥
        try {
            HybridConfig.register();
        } catch (GeneralSecurityException e) {
            logger.error("KeyGen Initialization Failed: " + e.getMessage());
            System.err.println("KeyGen Initialization Failed: " + e.getMessage());
            System.exit(1);
        }
        if (result.hasOption("k")) {
            try {//导入密钥
                String keyStoreFilePath = result.getOptionValue("k");
                KeyFile keyFile = KeyFile.parseFrom(GRpcUtil.readBytesFromFile(keyStoreFilePath));
                account.id = keyFile.getId();
                account.sk = GRpcUtil.getKeyKeysetHandle(keyFile.getKey());//私钥
                account.pk = account.sk.getPublicKeysetHandle();//公钥
            } catch (GeneralSecurityException e) {
                logger.error("General Key \"" + result.getOptionValue("k") + "\" Failed: " + e.getMessage());
                System.err.println("General Key \"" + result.getOptionValue("k") + "\" Failed: " + e.getMessage());
                System.exit(1);
            } catch (IOException e) {
                logger.error("Key File \"" + result.getOptionValue("k") + "\" Error: " + e.getMessage());
                System.err.println("Key File \"" + result.getOptionValue("k") + "\" Error: " + e.getMessage());
                System.exit(1);
            }
            logger.info(" - Key File \"" + result.getOptionValue("k") + "\" Loaded");
        } else {
            //生成随机密钥
            try {
                System.out.print("Set your account name:");
                Scanner scanner = new Scanner(System.in);
                String accountName = scanner.nextLine();
                if (accountName.length() > 0)
                    account.name = accountName;
                account.sk = KeysetHandle.generateNew(KeyTemplates.get("ECIES_P256_COMPRESSED_HKDF_HMAC_SHA256_AES128_GCM"));//私钥
                account.pk = account.sk.getPublicKeysetHandle();//公钥
            } catch (GeneralSecurityException e) {
                logger.error("KeyGen Error: " + e.getMessage());
                System.err.println("KeyGen Error: " + e.getMessage());
                System.exit(1);
            }
            keyFileSaveFlag = true;
        }
    }

    //运行客户端线程
    public void run() throws InterruptedException {
        logger.info("Running in client mode");
        Thread threadClient = new Thread(() -> {
            // 与服务器连接的通道
            ManagedChannel channel = ManagedChannelBuilder.forTarget(connectTarget).usePlaintext().build();
            GRpcClient client = new GRpcClient(channel);
            client.setAccountInfo(account.id, account.name, account.pk, account.sk);
            //登录
            try {
                CountDownLatch finishLatch = client.login();
                if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                    logger.error("Login can not finish within 1 minutes");
                    System.err.println("Login can not finish within 1 minutes");
                }
                if (client.isLoginSuccessful()) {
                    logger.info("Login");
                } else {
                    logger.error("Login Failed");
                    System.err.println("Login Failed");
                    System.exit(1);
                }
            } catch (InterruptedException e) {
                logger.error("Login failed: " + e.getMessage());
                System.err.println("Login failed: " + e.getMessage());
                System.exit(1);
            }
            if (keyFileSaveFlag) {
                //保存密钥
                try {
                    String keyStoreFilePath = account.name + ".key";
                    KeyFile keyFile = KeyFile.newBuilder()
                            .setId(GRpcClient.getAccountId()).setKey(GRpcUtil.getKeyByteString(account.sk))
                            .build();
                    GRpcUtil.writeBytesToFile(keyFile.toByteArray(), keyStoreFilePath);
                } catch (IOException e) {
                    logger.error("Save Key File \"" + account.name + ".key\" Failed: " + e.getMessage());
                    System.err.println("Save Key File \"" + account.name + ".key\" Failed: " + e.getMessage());
                    System.exit(1);
                }
                logger.info(" - Key File \"" + account.name + ".key\" Generated and Saved");
            }
            Scanner scanner = new Scanner(System.in);
            logger.info("Receiver: " + client.getReceiver());
            //循环，直到登出
            do {
                //发送消息
                System.out.print("Send to [" + client.getReceiver() + "]: ");
                String inputStr = scanner.nextLine();
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
                    logger.warn(warnMessage + " failed: " + e.getMessage());
                }
            } while (!completeFlag);

            try {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("Channel Shutdown Error: " + e.getMessage());
            }
            System.exit(0);
        });
        threadClient.start();
        threadClient.join();
    }

    //账户信息
    private static class Account {
        public long id = -1;
        public String name = GRpcUtil.getRandomString(6);
        public KeysetHandle pk = null;
        public KeysetHandle sk = null;
    }
}
