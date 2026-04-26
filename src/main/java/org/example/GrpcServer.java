package org.example;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
public class GrpcServer {
    private final Server server;

    private ABCIApp service;

    public GrpcServer(BindableService service, int port) {
        this.service = (ABCIApp)service;
        this.server = ServerBuilder.forPort(port)
                .addService(service)
                .build();
    }

    public void start() throws IOException {
        server.start();
        System.out.println("gRPC server started, listening on 26658");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("shutting down gRPC server");
            server.shutdown();
            this.service.getState().close();
            System.out.println("server shut down");
        }));
    }

    public void blockUntilShutdown() throws InterruptedException {
        server.awaitTermination();
    }
}
