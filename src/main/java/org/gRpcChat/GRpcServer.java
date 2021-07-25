package org.gRpcChat;

import com.google.crypto.tink.KeysetHandle;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Struct;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class GRpcServer {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());
    static HashMap<String, UserInfo> register = new HashMap<>();
    private final int port;
    private Server server;

    private enum PostType {typeString, typeRepeated}

    public GRpcServer(int port) {
        this.port = port;
    }

    //启动Server
    public void start() throws IOException {
        register.put("#Everyone", new UserInfo());
        server = ServerBuilder.forPort(port)
                .addService(new StringMessageImpl())
                .build()
                .start();
        logger.info("Service started, listening on " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown hook.
            logger.error("*** shutting down gRPC server since JVM is shutting down");
            try {
                GRpcServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            logger.error("*** server shut down");
        }));
    }

    //关闭Server
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    //由于grpc库使用守护线程，所以在主线程上等待终止。
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    //Server提供的服务
    static class StringMessageImpl extends StringMessageGrpc.StringMessageImplBase {
        @Override
        public StreamObserver<Pack> postPackage(StreamObserver<Pack> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(Pack value) {
                    String message = "";
                    PostType postType = PostType.typeString;
                    boolean completeFlag = false;
                    String act = value.getAct();
                    //服务器动作
                    switch (act) {
                        //收到用户登录消息
                        case "#login" -> {
                            if (!register.containsKey(value.getSender())) {//登录
                                postType = PostType.typeRepeated;
                                //保存用户信息
                                UserInfo newUserInfo = new UserInfo();
                                newUserInfo.name = value.getUserInfoList(0).getName();
                                newUserInfo.pk = value.getUserInfoList(0).getPk();
                                newUserInfo.skHash = value.getUserInfoList(0).getSkHash();
                                newUserInfo.stream = responseObserver;
                                register.put(newUserInfo.name, newUserInfo);
                                message = "Login Successful";
                                logger.info("User " + newUserInfo.name + " Login");
                                //广播用户登录消息
                                UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                        .setName(newUserInfo.name).setPk(newUserInfo.pk)
                                        .build();
                                for (HashMap.Entry<String, UserInfo> user : register.entrySet()) {//遍历register
                                    if (user.getKey().equals("#Everyone") || user.getKey().equals(newUserInfo.name))
                                        continue;
                                    UserInfo userInfo = user.getValue();
                                    Pack loginMsgPack = Pack.newBuilder().setAct("SP_loginMsg")
                                            .setSender("Server").setReceiver(user.getKey())
                                            .setMessage(GRpcUtil.toByteString(newUserInfo.name))
                                            .addUserInfoList(userInfoPack).build();
                                    userInfo.stream.onNext(loginMsgPack);//向某个在线用户发送群发消息
                                }
                            } else {
                                message = "Login Failed: Account name has been used";
                                completeFlag = true;
                            }
                        }
                        //收到用户下线消息
                        case "#logout" -> {
                            for (HashMap.Entry<String, UserInfo> user : register.entrySet()) {//遍历register
                                if (user.getKey().equals("#Everyone"))
                                    continue;
                                if (user.getValue().stream.equals(responseObserver)) {
                                    logger.info("User " + user.getKey() + " logout");
                                    register.remove(user.getKey());//从记录中移除
                                    //广播用户下线消息
                                    UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                            .setName(user.getValue().name).setPk(user.getValue().pk)
                                            .build();
                                    for (HashMap.Entry<String, UserInfo> entry : register.entrySet()) {//遍历register
                                        if (entry.getKey().equals("#Everyone"))
                                            continue;
                                        UserInfo userInfo = entry.getValue();
                                        Pack loginMsgPack = Pack.newBuilder().setAct("SP_logoutMsg")
                                                .setSender("Server").setReceiver(entry.getKey())
                                                .setMessage(GRpcUtil.toByteString(user.getValue().name))
                                                .addUserInfoList(userInfoPack).build();
                                        userInfo.stream.onNext(loginMsgPack);//向某个在线用户发送群发消息
                                    }
                                    break;
                                }
                            }
                            message = "Logout Successful";
                            completeFlag = true;
                        }
                        //转发用户发送的信息
                        case "#post" -> {
                            if (register.containsKey(value.getReceiver())) {//检查接收对象是否在线
                                UserInfo userForwardTo = register.get(value.getReceiver());
                                Pack forwardPack = Pack.newBuilder().setAct("SP_forward")
                                        .setSender(value.getSender()).setReceiver(value.getReceiver())
                                        .setMessage(value.getMessage()).build();
                                userForwardTo.stream.onNext(forwardPack);//直接转给收件方
                                message = "Send Successful";
                                logger.info("Private Chat: " + value.getSender() + " -> " + value.getReceiver());
                            } else {
                                message = "Send Failed: User " + value.getReceiver() + " is offline";
                            }
                        }
                        //转发用户群发的消息
                        case "#broadcast" -> {
                            StringBuffer sb = new StringBuffer("Broadcast:    " + value.getSender() + " -> ");
                            for (HashMap.Entry<String, UserInfo> entry : register.entrySet()) {//遍历register
                                if (entry.getKey().equals("#Everyone"))
                                    continue;
                                UserInfo userForwardTo = entry.getValue();
                                Pack forwardPack = Pack.newBuilder().setAct("SP_broadcast")
                                        .setSender(value.getSender()).setReceiver(entry.getKey())
                                        .setMessage(value.getMessage()).build();
                                userForwardTo.stream.onNext(forwardPack);//向某个在线用户发送群发消息
                                sb.append(entry.getKey()).append("; ");
                            }
                            logger.info(sb.toString());
                            message = "Broadcast Successful";
                        }
                    }
                    Pack responsePack = null;
                    //服务器响应
                    switch (postType) {
                        //发送普通通知
                        case typeString -> {
                            responsePack = Pack.newBuilder()
                                    .setAct("SR_String").setMessage(GRpcUtil.toByteString(message))
                                    .setSender("Server").setReceiver(value.getSender())
                                    .build();
                        }
                        //发送在线成员信息数组
                        case typeRepeated -> {
                            Pack.Builder responsePackBuilder = Pack.newBuilder();
                            responsePackBuilder.setAct("SR_UserList").setMessage(GRpcUtil.toByteString(message))
                                    .setSender("Server").setReceiver(value.getSender());
                            for (HashMap.Entry<String, UserInfo> entry : register.entrySet()) {//遍历register
                                //填充用户的姓名与公钥
                                if (entry.getKey().equals("#Everyone"))
                                    continue;
                                UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                        .setName(entry.getValue().name).setPk(entry.getValue().pk)
                                        .build();
                                responsePackBuilder.addUserInfoList(userInfoPack);
                            }
                            responsePack = responsePackBuilder.build();
                        }
                    }
                    responseObserver.onNext(responsePack);
                    if (completeFlag)
                        responseObserver.onCompleted();
                }

                @Override
                public void onError(Throwable t) {
                    for (HashMap.Entry<String, UserInfo> user : register.entrySet()) {//遍历register
                        if (user.getKey().equals("#Everyone"))
                            continue;
                        if (user.getValue().stream.equals(responseObserver)) {
                            logger.error("User " + user.getKey() + " Disconnected");
                            register.remove(user.getKey());//从记录中移除
                            //广播用户下线消息
                            UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                    .setName(user.getValue().name).setPk(user.getValue().pk)
                                    .build();
                            for (HashMap.Entry<String, UserInfo> entry : register.entrySet()) {//遍历register
                                if (entry.getKey().equals("#Everyone"))
                                    continue;
                                UserInfo userInfo = entry.getValue();
                                Pack loginMsgPack = Pack.newBuilder().setAct("SP_logoutMsg")
                                        .setSender("Server").setReceiver(entry.getKey())
                                        .setMessage(GRpcUtil.toByteString(user.getValue().name))
                                        .addUserInfoList(userInfoPack).build();
                                userInfo.stream.onNext(loginMsgPack);//向某个在线用户发送群发消息
                            }
                            break;
                        }
                    }
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
