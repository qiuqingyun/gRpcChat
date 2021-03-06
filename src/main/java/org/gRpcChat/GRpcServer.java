package org.gRpcChat;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

//Grpc服务端（转发服务器）
public class GRpcServer {
    private final static Logger logger = LoggerFactory.getLogger("Server");
    private static final HashMap<Long, UserInfo> register = new HashMap<>();
    private static ChatDatabase db;
    private Server server;
    private final static long serverId = 0;
    private final static long broadcastId = 0;

    private enum PostType {typeString, typeRepeated}

    //启动Server
    public void start(int port) throws IOException {
        String dbFileName = "userList.db";
        File dbFile = new File(dbFileName);
        boolean initFlag = dbFile.exists();
        db = new ChatDatabase(dbFileName);
        db.openDB();
        if (!initFlag)
            db.createTable();
        db.selectAll(register);
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
                            long userId = value.getUserInfoList(0).getId();
                            String userName = value.getUserInfoList(0).getName();
                            ByteString userPk = value.getUserInfoList(0).getPk();
                            String userSkHash = value.getUserInfoList(0).getSkHash();
                            boolean regStatus = false;
                            String loginName = null;
                            if (register.containsKey(userId)) {//登录
                                loginName = db.login(userId, userSkHash);
                                userName=loginName;
                            } else {//注册
                                userId = db.reg(userName, userPk, userSkHash);
                                regStatus = true;
                            }

                            if (loginName == null && (!regStatus)) {//用户已存在且登陆失败
                                message = "Login Failed: Authentication failure";
                                completeFlag = true;
                            } else {
                                postType = PostType.typeRepeated;
                                if (regStatus) {//新用户注册，保存用户信息
                                    UserInfo newUserInfo = new UserInfo();
                                    newUserInfo.setName(userName);
                                    newUserInfo.setPk(userPk);
                                    newUserInfo.setStream(responseObserver);
                                    newUserInfo.login();
                                    newUserInfo.setId(userId);
                                    register.put(userId, newUserInfo);
                                    message += ("Registration Successful:" + userId);
                                    logger.info("New User " + userId + " Registration");
                                } else {//老用户登录，更新信息
                                    UserInfo userInfo = register.get(userId);
                                    userInfo.setStream(responseObserver);
                                    userInfo.login();
                                    message += ("Login Successful:" + loginName);
                                    logger.info("User " + userId + " Login");
                                }
                                //广播用户登录消息
                                UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                        .setId(userId).setName(userName).setPk(userPk)
                                        .build();
                                Pack loginMsgPack = Pack.newBuilder().setAct("SP_loginMsg")
                                        .setSender(serverId).setReceiver(db.getOriginId())
                                        .setMessage(GRpcUtil.toByteString("login"))
                                        .addUserInfoList(userInfoPack)
                                        .build();
                                for (HashMap.Entry<Long, UserInfo> user : register.entrySet()) {//遍历register
                                    if (user.getKey().equals(db.getOriginId()) || user.getKey().equals(userId) || (!user.getValue().isOnline()))
                                        continue;
                                    user.getValue().getStream().onNext(loginMsgPack);//向某个在线用户发送群发消息
                                }
                            }
                        }
                        //收到用户下线消息
                        case "#logout" -> {
                            for (HashMap.Entry<Long, UserInfo> user : register.entrySet()) {//遍历register
                                if (user.getKey().equals(db.getOriginId()) || !user.getValue().isOnline())
                                    continue;
                                if (user.getValue().getStream().equals(responseObserver)) {
                                    logger.info("User " + user.getKey() + " logout");
                                    //设置离线
                                    user.getValue().logout();
                                    //广播用户下线消息
                                    UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                            .setId(user.getValue().getId()).setName(user.getValue().getName())
                                            .setPk(user.getValue().getPk())
                                            .build();
                                    Pack loginMsgPack = Pack.newBuilder().setAct("SP_logoutMsg")
                                            .setSender(serverId).setReceiver(db.getOriginId())
                                            .setMessage(GRpcUtil.toByteString("logout"))
                                            .addUserInfoList(userInfoPack)
                                            .build();
                                    for (HashMap.Entry<Long, UserInfo> entry : register.entrySet()) {//遍历register
                                        if (entry.getKey().equals(db.getOriginId()) || (!entry.getValue().isOnline()))
                                            continue;
                                        entry.getValue().getStream().onNext(loginMsgPack);//向某个在线用户发送群发消息
                                    }
                                    break;
                                }
                            }
                            message = "Logout Successful";
                            completeFlag = true;
                        }
                        //转发用户发送的信息
                        case "#post" -> {
                            if (register.containsKey(value.getReceiver()) && register.get(value.getReceiver()).isOnline()) {//检查接收对象是否在线
                                UserInfo userForwardTo = register.get(value.getReceiver());
                                Pack forwardPack = Pack.newBuilder().setAct("SP_forward")
                                        .setSender(value.getSender()).setReceiver(value.getReceiver())
                                        .setMessage(value.getMessage()).build();
                                userForwardTo.getStream().onNext(forwardPack);//直接转给收件方
                                message = "Send Successful";
                                logger.info("Private Chat: " + value.getSender() + " -> " + value.getReceiver());
                            } else {
                                message = "Send Failed: User " + value.getReceiver() + " is offline";
                            }
                        }
                        //转发用户群发的消息
                        case "#broadcast" -> {
                            StringBuffer sb = new StringBuffer("Broadcast:    " + value.getSender() + " -> ");
                            Pack forwardPack = Pack.newBuilder().setAct("SP_broadcast")
                                    .setSender(value.getSender()).setReceiver(broadcastId)
                                    .setMessage(value.getMessage())
                                    .build();
                            for (HashMap.Entry<Long, UserInfo> entry : register.entrySet()) {//遍历register
                                if (entry.getKey().equals(db.getOriginId()) || (!entry.getValue().isOnline()))
                                    continue;
                                entry.getValue().getStream().onNext(forwardPack);//向某个在线用户发送群发消息
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
                        case typeString -> responsePack = Pack.newBuilder()
                                .setAct("SR_String").setMessage(GRpcUtil.toByteString(message))
                                .setSender(serverId).setReceiver(value.getSender())
                                .build();
                        //发送在线成员信息数组
                        case typeRepeated -> {
                            Pack.Builder responsePackBuilder = Pack.newBuilder();
                            responsePackBuilder.setAct("SR_UserList").setMessage(GRpcUtil.toByteString(message))
                                    .setSender(serverId).setReceiver(value.getSender());
                            for (HashMap.Entry<Long, UserInfo> entry : register.entrySet()) {//遍历register
                                //填充用户的姓名与公钥
                                if (entry.getKey().equals(db.getOriginId())
                                        || (!entry.getValue().isOnline()))
                                    continue;
                                UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                        .setId(entry.getValue().getId()).setName(entry.getValue().getName()).setPk(entry.getValue().getPk())
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
                    for (HashMap.Entry<Long, UserInfo> user : register.entrySet()) {//遍历register
                        if (user.getKey().equals(db.getOriginId()) || !user.getValue().isOnline())
                            continue;
                        if (user.getValue().getStream().equals(responseObserver)) {
                            logger.warn("User " + user.getKey() + " Disconnected");
                            user.getValue().logout();//设置离线
                            //广播用户下线消息
                            UserInfoPack userInfoPack = UserInfoPack.newBuilder()
                                    .setId(user.getValue().getId()).setName(user.getValue().getName()).setPk(user.getValue().getPk())
                                    .build();
                            Pack loginMsgPack = Pack.newBuilder().setAct("SP_logoutMsg")
                                    .setSender(serverId).setReceiver(db.getOriginId())
                                    .setMessage(GRpcUtil.toByteString("logout"))
                                    .addUserInfoList(userInfoPack)
                                    .build();
                            for (HashMap.Entry<Long, UserInfo> entry : register.entrySet()) {//遍历register
                                if (entry.getKey().equals(db.getOriginId()) || (!entry.getValue().isOnline()))
                                    continue;
                                entry.getValue().getStream().onNext(loginMsgPack);//向某个在线用户发送群发消息
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
