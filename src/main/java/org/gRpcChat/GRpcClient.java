package org.gRpcChat;

import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.LoggerFactory;

import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public class GRpcClient {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());
    private final StringMessageGrpc.StringMessageBlockingStub blockingStub;
    private final StringMessageGrpc.StringMessageStub asyncStub;
    private final String name;
    private final StreamObserver<Pack> requestObserver;
    private CountDownLatch finishLatch = null;

    /**
     * Construct client for accessing HelloWorld server using the existing channel.
     */
    public GRpcClient(Channel channel, String name) {
        // 'channel' here is a Channel, not a ManagedChannel, so it is not this code's responsibility to
        // shut it down.
        // Passing Channels to code makes code easier to test and makes it easier to reuse Channels.
        blockingStub = StringMessageGrpc.newBlockingStub(channel);
        asyncStub = StringMessageGrpc.newStub(channel);
        this.name = name;
        requestObserver =
                asyncStub.postPackage(
                        new StreamObserver<>() {
                            @Override
                            public void onNext(Pack value) {
                                System.out.print("\rFrom [" + value.getSender() + "]: Message [" + value.getMessage() + "]  \nSend(#act/Name@Message): ");
                                switch (value.getAct()) {
                                    case "SR_RepeatedString" -> {
                                        List<String> list = value.getStringListList();
                                        int listSize = value.getStringListCount();
                                        System.out.println("\r + User List                                          ");
                                        for (int i = 0; i < listSize; ++i)
                                            System.out.println(" | User " + (i + 1) + ": " + value.getStringList(i));
                                        System.out.println(" + End of the User List\n");
                                    }
                                    default -> {

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
                                System.out.print("\r                                           ");
                                finishLatch.countDown();
                            }
                        }
                );
    }

    // 发送信息
    public CountDownLatch post(String act, String target, String message) {
        finishLatch = new CountDownLatch(1);
        try {
            Pack request = Pack.newBuilder()
                    .setAct(act).setMessage(message)
                    .setSender(this.name).setReceiver(target)
                    .build();
            requestObserver.onNext(request);
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }
        // return the latch while receiving happens asynchronously
        return finishLatch;
    }

    //登录
    public CountDownLatch login() {
        logger.info("Login");
        finishLatch = new CountDownLatch(1);
        try {
            Pack request = Pack.newBuilder()
                    .setAct("#login").setMessage(new java.util.Date().toString())
                    .setSender(this.name).setReceiver("Server")
                    .build();
            requestObserver.onNext(request);
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }
        return finishLatch;
    }

    //登出
    public CountDownLatch logout() {
        logger.info("Logout");
        finishLatch = new CountDownLatch(1);
        try {
            Pack request = Pack.newBuilder()
                    .setAct("#logout").setMessage(new java.util.Date().toString())
                    .setSender(this.name).setReceiver("Server")
                    .build();
            requestObserver.onNext(request);
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }
        return finishLatch;
    }

    //加载在线用户名单
    public CountDownLatch loadUserList() {
        finishLatch = new CountDownLatch(1);
        try {
            Pack request = Pack.newBuilder()
                    .setAct("#loadUserList").setMessage(new java.util.Date().toString())
                    .setSender(this.name).setReceiver("Server")
                    .build();
            requestObserver.onNext(request);
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }
        return finishLatch;
    }
}