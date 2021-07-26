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
import java.util.*;
import java.util.concurrent.CountDownLatch;

//Grpc客户端
public class GRpcClient {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());
    private static final Account accountInfo = new Account();//用户信息
    private final StreamObserver<Pack> requestObserver;
    private CountDownLatch finishLatch = null;
    private final HashMap<String, ByteString> userList = new HashMap<>();
    private final Receiver receiver = new Receiver();
    private final byte[] contextInfo = new byte[0];
    private boolean loginSuccessful = false;

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
                                        System.out.println("\r - Receive Message: [" + message + "]\n - From [" + value.getSender() + "]");
                                    }
                                    //接收群发消息
                                    case "SP_broadcast" -> {
                                        System.out.println("\r - Receive Broadcast: [" + value.getMessage().toStringUtf8() + "]\n - From [" + value.getSender() + "]");
                                    }
                                    //接收在线用户列表
                                    case "SR_UserList" -> {
                                        if (value.getMessage().toStringUtf8().equals("Login Successful"))
                                            loginSuccessful = true;
                                        System.out.println("\rNotice: [" + value.getMessage().toStringUtf8() + "]");
                                        userList.clear();
                                        int listSize = value.getUserInfoListCount();
                                        for (int i = 0; i < listSize; ++i) {
                                            userList.put(value.getUserInfoList(i).getName(), value.getUserInfoList(i).getPk());//本地保存在线用户的公钥
                                        }
                                    }
                                    //接收用户登录消息
                                    case "SP_loginMsg" -> {
                                        userList.put(value.getUserInfoList(0).getName(), value.getUserInfoList(0).getPk());//本地保存在线用户的公钥
                                    }
                                    //接收用户下线消息
                                    case "SP_logoutMsg" -> {
                                        userList.remove(value.getUserInfoList(0).getName(), value.getUserInfoList(0).getPk());//本地保存在线用户的公钥
                                    }
                                    //普通服务器通知
                                    case "SR_String" -> {
                                        System.out.println("\rNotice: [" + value.getMessage().toStringUtf8() + "]");
                                    }
                                    //未知消息
                                    default -> {
                                        System.out.println("\rUnknown message: [" + value.getMessage().toStringUtf8() + "]");
                                    }
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
                        .setSender(accountInfo.name).setReceiver("#Everyone")
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
                        .setSender(accountInfo.name).setReceiver(this.receiver.name)
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
            UserInfoPack userInfoPack = UserInfoPack.newBuilder().setName(accountInfo.name)
                    .setPk(GRpcUtil.getKeyByteString(accountInfo.pk)).setSkHash(accountInfo.skHash)
                    .build();
            Pack request = Pack.newBuilder()
                    .setAct("#login").setMessage(GRpcUtil.toByteString(GRpcUtil.getTimeStamp()))
                    .setSender(accountInfo.name).setReceiver("Server").addUserInfoList(userInfoPack)
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
                    .setSender(accountInfo.name).setReceiver("Server")
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
        for (Map.Entry<String, ByteString> user : userList.entrySet()) {
            if (user.getKey().equals(accountInfo.name))
                continue;
            sb.append("| User ").append(userIndex++).append(": ").append(user.getKey()).append("\n");
        }
        sb.append("\n");
        System.out.println(sb);
    }

    //设置接收者
    public void setReceiver() {
        HashMap<String, ByteString> userList = this.getUserList();
        int usersCount = userList.size();
        int userIndex = 1;
        StringBuffer sb = new StringBuffer("\rUser[0]: #Everyone\n");
        ArrayList<String> userNameList = new ArrayList<>();
        for (Map.Entry<String, ByteString> user : userList.entrySet()) {
            if (user.getKey().equals(accountInfo.name))
                continue;
            sb.append("User[").append(userIndex++).append("]: ").append(user.getKey()).append("\n");
            userNameList.add(user.getKey());
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
    private static class Receiver {
        public String name = null;
        public KeysetHandle pk = null;
    }

    //账户信息
    private static class Account {
        public String name = null;
        public KeysetHandle pk = null;
        public KeysetHandle sk = null;
        public String skHash = null;
    }

    //设置接收者信息
    private void setReceiver(String name) {
        this.receiver.name = name;
        if (name != null) {
            try {
                this.receiver.pk = GRpcUtil.getKeyKeysetHandle(userList.get(name));
            } catch (GeneralSecurityException | IOException e) {
                e.printStackTrace();
            }
        } else
            this.receiver.pk = null;
    }

    //获取接收者名称
    public String getReceiver() {
        if (this.receiver.name != null)
            return this.receiver.name;
        return "#Everyone";
    }

    //设置账户信息(账户名称，公钥，私钥)
    public void setAccountInfo(String name, KeysetHandle pk, KeysetHandle sk) {
        accountInfo.name = name;
        accountInfo.pk = pk;
        accountInfo.sk = sk;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            CleartextKeysetHandle.write(accountInfo.sk, BinaryKeysetWriter.withOutputStream(baos));
        } catch (IOException e) {
            e.printStackTrace();
        }
        accountInfo.skHash = GRpcUtil.SHA256(baos.toString());
    }

    public HashMap<String, ByteString> getUserList() {
        return userList;
    }
    public boolean isLoginSuccessful() {
        return loginSuccessful;
    }
}