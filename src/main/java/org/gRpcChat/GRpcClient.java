package org.gRpcChat;

import com.google.crypto.tink.*;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.LoggerFactory;

import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CountDownLatch;

//Grpc客户端
public class GRpcClient {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());
    private static final Account accountInfo = new Account();//用户信息
    private final StreamObserver<Pack> requestObserver;
    private CountDownLatch finishLatch = null;
    private final HashMap<Long, User> userList = new HashMap<>();
    private final User receiver = new User();
    private final byte[] contextInfo = new byte[0];
    private boolean loginSuccessful = false;
    private final static long serverId = 0;
    private static String accountName;

    //初始化
    public GRpcClient(Channel channel) {
        StringMessageGrpc.StringMessageStub asyncStub = StringMessageGrpc.newStub(channel);
        requestObserver =
                asyncStub.postPackage(
                        new StreamObserver<>() {
                            @Override
                            public void onNext(Pack value) {
                                switch (value.getAct()) {
                                    //接收私聊消息
                                    case "SP_forward" -> {
                                        //混合解密
                                        String message = null;
                                        try {
                                            HybridDecrypt decryptor = GRpcClient.accountInfo.sk.getPrimitive(HybridDecrypt.class);
                                            byte[] plaintext = decryptor.decrypt(value.getMessage().toByteArray(), contextInfo);
                                            message = new String(plaintext, StandardCharsets.UTF_8);
                                        } catch (GeneralSecurityException ex) {
                                            System.err.println("Cannot create primitive, got error: " + ex);
                                            System.exit(1);
                                        }
                                        System.out.println("\r - Receive Message: [" + message + "]\n - From [" + userList.get(value.getSender()).name + "]");
                                    }
                                    //接收群发消息
                                    case "SP_broadcast" -> {
                                        String message = value.getMessage().toStringUtf8();
                                        String sender = userList.get(value.getSender()).name;
                                        System.out.println("\r - Receive Broadcast: [" + message + "]\n - From [" + sender + "]");
                                    }
                                    //登录成功，并接收在线用户列表
                                    case "SR_UserList" -> {
                                        String message = value.getMessage().toStringUtf8();
                                        if (message.contains("Login Successful")) {
                                            loginSuccessful = true;
                                            accountInfo.name = message.split(":")[1];
                                            System.out.println("\rNotice: [Welcome back, " + message.split(":")[1] + "]");
                                        } else if (message.contains("Registration Successful")) {
                                            loginSuccessful = true;
                                            accountInfo.id = Long.parseLong(message.split(":")[1]);
                                            System.out.println("\rNotice: [Hello " + accountInfo.name + ", your id is " + message.split(":")[1] + "]");
                                        }
                                        accountName = accountInfo.name + "@" + accountInfo.id;
                                        userList.clear();
                                        int listSize = value.getUserInfoListCount();
                                        for (int i = 0; i < listSize; ++i) {
                                            String userName = value.getUserInfoList(i).getName() + "@" + value.getUserInfoList(i).getId();
                                            User user = new User(value.getUserInfoList(i).getId(), userName, value.getUserInfoList(i).getPk());
                                            userList.put(value.getUserInfoList(i).getId(), user);//本地保存在线用户的公钥
                                        }
                                    }
                                    //接收用户登录消息
                                    case "SP_loginMsg" -> {
                                        String userName = value.getUserInfoList(0).getName() + "@" + value.getUserInfoList(0).getId();
                                        User user = new User(value.getUserInfoList(0).getId(), userName, value.getUserInfoList(0).getPk());
                                        userList.put(value.getUserInfoList(0).getId(), user);
                                    }
                                    //接收用户下线消息
                                    case "SP_logoutMsg" -> {
                                        String userName = value.getUserInfoList(0).getName() + "@" + value.getUserInfoList(0).getId();
                                        userList.remove(value.getUserInfoList(0).getId());
                                    }
                                    //普通服务器通知
                                    case "SR_String" -> System.out.println("\rNotice: [" + value.getMessage().toStringUtf8() + "]");
                                    //未知消息
                                    default -> System.out.println("\rUnknown message: [" + value.getMessage().toStringUtf8() + "]");
                                }
                                finishLatch.countDown();
                            }

                            @Override
                            public void onError(Throwable t) {
                                logger.warn("PostPackage Error: " + Status.fromThrowable(t));
                                finishLatch.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                finishLatch.countDown();
                            }
                        }
                );
    }

    // 发送信息
    public CountDownLatch post(String message) {
        finishLatch = new CountDownLatch(1);
        if (this.receiver.name == null || this.receiver.pk == null) {
            //群发(不加密)
            try {
                Pack request = Pack.newBuilder().setAct("#broadcast")
                        .setSender(accountInfo.id).setReceiver(serverId)
                        .setMessage(GRpcUtil.toByteString(message)).build();
                requestObserver.onNext(request);
            } catch (RuntimeException e) {
                // Cancel RPC
                requestObserver.onError(e);
                e.printStackTrace();
            }
        } else {
            try {
                //混合加密
                HybridEncrypt hybridEncrypt = this.receiver.pk.getPrimitive(HybridEncrypt.class);
                byte[] ciphertext = hybridEncrypt.encrypt(message.getBytes(StandardCharsets.UTF_8), contextInfo);

                Pack request = Pack.newBuilder().setAct("#post")
                        .setSender(accountInfo.id).setReceiver(this.receiver.id)
                        .setMessage(ByteString.copyFrom(ciphertext)).build();
                requestObserver.onNext(request);
            } catch (RuntimeException | GeneralSecurityException e) {
                // Cancel RPC
                requestObserver.onError(e);
                e.printStackTrace();
            }
        }
        // return the latch while receiving happens asynchronously
        return finishLatch;
    }

    //登录
    public CountDownLatch login() {
        logger.info("Logging in");
        finishLatch = new CountDownLatch(1);
        try {
            UserInfoPack userInfoPack = UserInfoPack.newBuilder().setId(accountInfo.id).setName(accountInfo.name)
                    .setPk(GRpcUtil.getKeyByteString(accountInfo.pk)).setSkHash(accountInfo.skHash)
                    .build();
            Pack request = Pack.newBuilder()
                    .setAct("#login").setMessage(GRpcUtil.toByteString(GRpcUtil.getTimeStamp()))
                    .setSender(accountInfo.id).setReceiver(serverId).addUserInfoList(userInfoPack)
                    .build();
            requestObserver.onNext(request);
        } catch (IOException | RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            e.printStackTrace();
        }
        return finishLatch;
    }

    //登出
    public CountDownLatch logout() {
        logger.info("Logout");
        finishLatch = new CountDownLatch(1);
        try {
            Pack request = Pack.newBuilder()
                    .setAct("#logout").setMessage(GRpcUtil.toByteString(GRpcUtil.getTimeStamp()))
                    .setSender(accountInfo.id).setReceiver(serverId)
                    .build();
            requestObserver.onNext(request);
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            e.printStackTrace();
        }
        return finishLatch;
    }

    //展示在线用户名单
    public void showUserList() {
        StringBuffer sb = new StringBuffer("\r+ Online Users:\n");
        int userIndex = 1;
        for (Map.Entry<Long, User> user : userList.entrySet()) {
            sb.append("| User ").append(userIndex++).append(": ").append(user.getValue().name).append("\n");
        }
        sb.append("\n");
        System.out.println(sb);
    }

    //设置接收者
    public void setReceiver() {
        int usersCount = userList.size();
        int userIndex = 1;
        StringBuffer sb = new StringBuffer("\rUser[0]: #Everyone\n");
        ArrayList<String> userNameList = new ArrayList<>();
        for (Map.Entry<Long, User> user : userList.entrySet()) {
            if (user.getKey().equals(accountInfo.id))
                continue;
            sb.append("User[").append(userIndex++).append("]: ").append(user.getValue().name).append("\n");
            userNameList.add(user.getValue().name);
        }
        sb.append("Receiver index: ");
        System.out.print(sb);

        Scanner scanner = new Scanner(System.in);
        int inputInt;
        //输入合法性验证
        if (scanner.hasNextInt()) {
            inputInt = scanner.nextInt();
        } else {
            inputInt = -1;
        }
        //设置接收者
        if (inputInt > 0 && inputInt <= usersCount) {
            this.setReceiver(userNameList.get(inputInt - 1));
            System.out.println("Set Receiver User " + userNameList.get(inputInt - 1) + " Succeeded");
            logger.info("Set new Receiver: " + userNameList.get(inputInt - 1));
        } else if (inputInt == 0) {//设置群发
            this.setReceiver(null);
            System.out.println("Set Receiver User #Everyone Succeeded");
            logger.info("Set new Receiver: #Everyone");
        } else {
            System.out.println("Set Receiver Failed");
        }
    }

    //接收方信息
    private static class User {
        public long id = -1;
        public String name = null;
        public KeysetHandle pk = null;

        public User() {
        }

        public User(long id, String name, ByteString pk) {
            this.id = id;
            this.name = name;
            try {
                this.pk = GRpcUtil.getKeyKeysetHandle(pk);
            } catch (GeneralSecurityException | IOException e) {
                logger.error("Load User " + this.name + "'s Public Key Error: " + e);
            }
        }
    }

    //账户信息
    private static class Account {
        public long id = -1;
        public String name = "";
        public KeysetHandle pk = null;
        public KeysetHandle sk = null;
        public String skHash = null;
    }

    //设置接收者信息
    private void setReceiver(String receiverName) {
        if (receiverName != null) {
            this.receiver.name = receiverName.substring(0, receiverName.lastIndexOf("@"));
            this.receiver.id = Long.parseLong(receiverName.substring(receiverName.lastIndexOf("@") + 1));
            this.receiver.pk = userList.get(this.receiver.id).pk;
        } else {
            this.receiver.name = null;
            this.receiver.id = -1;
            this.receiver.pk = null;
        }
    }

    //获取接收者名称
    public String getReceiver() {
        if (this.receiver.name != null)
            return (this.receiver.name + "@" + this.receiver.id);
        return "#Everyone";
    }

    //设置账户信息
    public void setAccountInfo(long id, String name, KeysetHandle pk, KeysetHandle sk) {
        accountInfo.name = name;
        accountInfo.id = id;
        accountInfo.pk = pk;
        accountInfo.sk = sk;
        try {
            accountInfo.skHash = GRpcUtil.keyHash(accountInfo.sk);
        } catch (IOException e) {
            logger.error("Load Private Key Failed: " + e);
        } catch (NoSuchAlgorithmException e) {
            logger.error("Load SHA256 Failed: " + e);
        }
    }

    public static long getAccountId() {
        return accountInfo.id;
    }

    public HashMap<Long, User> getUserList() {
        return userList;
    }

    public boolean isLoginSuccessful() {
        return loginSuccessful;
    }
}