package com.dsvl.flood;

import org.junit.Test;

import java.net.UnknownHostException;
import java.util.List;

import static org.junit.Assert.*;

public class NodeTest {

    @Test
    public void search() {
        try {
            Node node=new Node("127.0.0.1",55555,   "DSVL",4500,45555);
            MessageObject msgObj=new MessageObject();
            msgObj.setHops(1);
            msgObj.setFile_name("of_mario");
            List<File> search_results=node.search(msgObj);
            for (File search_result : search_results) {
                System.out.println(search_result.getFileName());
            }
            assertEquals("Lord of the Rings",search_results.get(0).getFileName());
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }
}