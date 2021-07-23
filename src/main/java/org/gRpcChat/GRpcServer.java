package org.gRpcChat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class GRpcServer {
    private final static Logger logger = LoggerFactory.getLogger(GRpcServer.class.getName());
    static HashMap<String, StreamObserver<Pack>> register = new HashMap<>();
    private final int port;
    private Server server;

    public GRpcServer(int port) {
        this.port = port;
    }

    //启动Server
    public void start() throws IOException {
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
                    boolean sendFlag=true;
                    boolean completeFlag = false;
                    String act = value.getAct();
                    switch (act) {
                        case "#login" -> {//登录
                            if (!register.containsKey(value.getSender())) {//登录
                                register.put(value.getSender(), responseObserver);
                                message = "Login successful";
                                logger.info("New user " + value.getSender() + " login");
                            } else {
                                message = "Username conflict";
                                completeFlag=true;
                            }
                        }
                        case "#logout"->{//登出
                            logger.info("User " + value.getSender() + " logout");
                            register.remove(value.getSender());//从记录中移除
                            message = "Logout successful";
                            completeFlag=true;
                        }
                        case "#post" -> {//发送信息
                            if (register.containsKey(value.getReceiver())) {
                                StreamObserver<Pack> userForwardTo = register.get(value.getReceiver());
                                userForwardTo.onNext(value);
                                message = "Sent";
                                logger.info(value.getSender() + " -> " + value.getReceiver() + ": [" + value.getMessage()+"]");
                            } else {
                                message = "No such user";
                            }
                        }
                    }
                    if (sendFlag) {
                        Pack responsePack = Pack.newBuilder()
                                .setAct("ServerResponse").setMessage(message)
                                .setSender("Server").setReceiver(value.getSender())
                                .build();
                        responseObserver.onNext(responsePack);
                        if(completeFlag)
                            responseObserver.onCompleted();
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Post Package Error.");
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
