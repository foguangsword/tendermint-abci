package org.example;

import java.io.IOException;


public class App 
{
    public static void main( String[] args ) throws IOException, InterruptedException
    {
        ABCIApp abciApp = new ABCIApp();
        GrpcServer server = new GrpcServer(abciApp, 26658);
        server.start();
        server.blockUntilShutdown();
    }
}
