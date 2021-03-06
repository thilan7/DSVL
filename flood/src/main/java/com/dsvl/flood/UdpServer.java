package com.dsvl.flood;

import com.dsvl.flood.exceptions.ErroneousResponseException;
import com.dsvl.flood.model.Log;
import com.dsvl.flood.service.LogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;

import static com.dsvl.flood.Constants.Status.REGISTERED;

/**
 * This {@code Component} starts with the application and attempts
 * to register with the bootstrap server.
 * <br>
 * If register attempt is failed for some reason
 * this will reattempt to register after waiting for five seconds.
 * <br>
 * Once registration is successful, it then starts listening to incoming
 * UDP messages forever.
 * This {@code CommandLineRunner} exists with the main application
 */
@Component
public class UdpServer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(UdpServer.class);

    @Autowired
    private Node node;

    @Autowired
    private LogRepository logRepository;

    @Override
    public void run(String... args) {

        while (!node.isRegistered()) {
            logger.info("Attempting to register with the bootstrap server");

            if (node.register()) {
                node.setRegistered(true);
                node.setStatus(REGISTERED);
                break;
            }

            // TODO: instead of sleeping for fixed 5 seconds apply some incremental logic
            logger.info("Sleeping for 5 seconds");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                //ignore
            }
        }

        //try to join the network 10 times and give up
        List<Neighbour> existingNodes = node.getExistingNodes();
        for (int i = 10; i > -1; i--) {
            logger.info("Attempting to connect to the network: trial {}", i);
            if (node.joinNetwork(existingNodes)) break;

            // TODO: instead of sleeping for fixed 5 seconds apply some incremental logic
            logger.info("Sleeping for 5 seconds");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                //ignore
            }
            if (i == 0) {
                logger.warn("Unable to connect to the network");
            }
        }

        // Create an ever running UDP receiving socket
        try (DatagramSocket socket = new DatagramSocket(node.getNodeUdpPort())) {
            logger.debug("UDP server started for incoming messages at port {}", node.getNodeUdpPort());
            byte[] buffer;
            while (true) {
                buffer = new byte[65536];
                DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(incomingPacket);
                if (incomingPacket.getData().length != 0) {
                    String receivedData = new String(incomingPacket.getData(), 0, incomingPacket.getLength());
                    Log log = new Log(
                            incomingPacket.getAddress().getHostAddress() + ":" + incomingPacket.getPort(),
                            "this",
                            "UDP",
                            receivedData
                    );
                    logRepository.save(log);
                    logger.info("Received UDP message from {}:{} {}", incomingPacket.getAddress().getHostAddress(), incomingPacket.getPort(), receivedData);
                    MessageDecoder messageDecoder = MessageDecoder.getInstance();
                    try {
                        MessageObject msgObject = messageDecoder.decode(incomingPacket.getData(), incomingPacket.getLength());
                        respond(msgObject, incomingPacket.getAddress(), incomingPacket.getPort());
                    } catch (ErroneousResponseException e) {
                        logger.info("Erroneous response received: {}", e.getMessage());
                    }
                }
            }
        } catch (SocketException e) {
            logger.error("Unable to open UDP socket for receiving", e);
        } catch (IOException e) {
            logger.error("Unable to receive UDP message", e);
        }

    }

    private void respond(MessageObject msgObject, InetAddress senderIP, int senderPort) {
        /*
         * Messages That Will Not Be Handled Here
         *
         * REGOK - handled at RegisterServiceImpl because we have nothing else to do until we until we reg with bootstrap server
         * JOINOK - handled at JoinServiceImpl because we have nothing else to do until we join the network
         *
         * */
        switch (msgObject.getMsgType()) {
            case "JOIN":
                Neighbour newNeighbour = msgObject.getJoinRequester();
                if (newNeighbour != null) {
                    node.getNeighbours().add(newNeighbour);
                    logger.info("New node added as neighbor, IP address: {}, port: {}",
                            newNeighbour.getAddress(), newNeighbour.getPort());
                    UdpHelper.sendMessage("0013 JOINOK 0", senderIP, senderPort);
                } else {
                    UdpHelper.sendMessage("0016 JOINOK 9999", senderIP, senderPort);
                }
                break;

            case "SER":
                logger.info("Search query has found, file name: {}, hops {}, IP address: {}, port: {}",
                        msgObject.getFile_name(), msgObject.getHops(),senderIP,senderPort);
                try {
                    List<File> search_results = node.search(msgObject);
                    String file_name_string = "";
                    String query = "SEROK" + " " + String.valueOf(search_results.size()) + " " + String.valueOf(senderIP) + " " + String.valueOf(senderPort) + " " + String.valueOf(msgObject.getHops()) + " ";
                    for (int i = 0; i < search_results.size(); i++) {
                        String fn = search_results.get(i).getFileName();
                        fn = fn.replaceAll(" ", "_");
                        file_name_string += fn + " ";
                    }
                    query += file_name_string;
                    String length = String.format("%04d", query.length() + 4);
                    query = length + " " + query;
                    System.out.println(query);
                    System.out.println(senderIP);
                    UdpHelper.sendMessage(query, senderIP, senderPort);
                }catch(Exception e){
                    String query="SEROK"+" "+"9998 "+ String.valueOf(senderIP) + " " + String.valueOf(senderPort) + " " + String.valueOf(msgObject.getHops());
                    // some other error
                    UdpHelper.sendMessage(query, senderIP, senderPort);
                }

            case "SEROK":

                if (msgObject.getNo_of_results()==0){
                    logger.info("Search response has recieved  Number of results: {}, hops {}, IP address: {}, TCPport: {}",
                            msgObject.getNo_of_results(), msgObject.getHops(),msgObject.getIp(),msgObject.getTcpPort());
                }
                else if (msgObject.getNo_of_results()==9999){
                    logger.info("Search response has recieved:  failure due to node unreachable");
                }
                else if (msgObject.getNo_of_results()==9998){
                logger.info("Search response has recieved:  some other error");
                }
                else {
                    logger.info("Search response has recieved  Number of results: {}, hops {}, IP address: {}, TCPport: {}",
                            msgObject.getNo_of_results(), msgObject.getHops(),msgObject.getIp(),msgObject.getTcpPort());
                }

            default:
                UdpHelper.sendMessage("0010 ERROR", senderIP, senderPort);
                break;
        }
    }
}
