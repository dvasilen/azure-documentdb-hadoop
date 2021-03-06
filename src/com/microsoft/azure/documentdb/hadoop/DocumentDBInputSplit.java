//------------------------------------------------------------
// Copyright (c) Microsoft Corporation.  All rights reserved.
//------------------------------------------------------------
package com.microsoft.azure.documentdb.hadoop;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputSplit;

import com.microsoft.azure.documentdb.ConnectionPolicy;
import com.microsoft.azure.documentdb.ConsistencyLevel;
import com.microsoft.azure.documentdb.Database;
import com.microsoft.azure.documentdb.Document;
import com.microsoft.azure.documentdb.DocumentClient;
import com.microsoft.azure.documentdb.DocumentCollection;
import com.microsoft.azure.documentdb.QueryIterable;

/**
 * An input split that represents one collection from documentdb. It reads data one page at a time and
 * sends one by one document to the mapper.
 * In order to be able to use it, you need to set the required configuration properties for the input split. 
 */
public class DocumentDBInputSplit extends InputSplit implements Writable, org.apache.hadoop.mapred.InputSplit {

    private static final Log LOG = LogFactory.getLog(DocumentDBWritable.class);
    private Text host, key, dbName, collName, query;
    private Iterator<Document> documentIterator;

    public DocumentDBInputSplit() {
        this.host = new Text();
        this.key = new Text();
        this.dbName = new Text();
        this.collName = new Text();
        this.query = new Text();
    }

    public DocumentDBInputSplit(String host, String key, String dbName, String collName, String query) {
        this.host = new Text(host);
        this.key = new Text(key);
        this.dbName = new Text(dbName);
        this.collName = new Text(collName);
        if (query == null) {
            query = "";
        }

        this.query = new Text(query);
    }

    public static List<InputSplit> getSplits(Configuration conf, String dbHost, String dbKey, String dbName,
            String[] collNames, String query) {
        int internalNumSplits = collNames.length;
        List<InputSplit> splits = new LinkedList<InputSplit>();
        for (int i = 0; i < internalNumSplits; i++) {
            splits.add(new DocumentDBInputSplit(dbHost, dbKey, dbName, collNames[i].trim(), query));
        }
        
        return splits;
    }

    @Override
    public long getLength() {
        return Integer.MAX_VALUE;
    }

    @Override
    public String[] getLocations() throws IOException {
        // Since we're pulling the data from DocumentDB, it's not localized
        // to any single node so just return localhost.
        return new String[] { "localhost" };
    }

    public String getCollectionName() {
        return this.collName.toString();
    }

    public void readFields(DataInput in) throws IOException {
        this.host.readFields(in);
        this.key.readFields(in);
        this.dbName.readFields(in);
        this.collName.readFields(in);
        this.query.readFields(in);
    }

    public void write(DataOutput out) throws IOException {
        this.host.write(out);
        this.key.write(out);
        this.dbName.write(out);
        this.collName.write(out);
        this.query.write(out);
    }

    public Iterator<Document> getDocumentIterator() throws IOException {
        if (this.documentIterator != null)
            return this.documentIterator;

        Database db;
        DocumentCollection coll;
        DocumentClient client;
        try {
            LOG.debug("Connecting to " + this.host + " and reading from collection " + this.collName);
            client = new DocumentClient(this.host.toString(), this.key.toString(), ConnectionPolicy.GetDefault(),
                    ConsistencyLevel.Session);

            QueryIterable<Database> dbIterable = client.queryDatabases(
                    String.format("select * from root r where r.id ='%s'", this.dbName), null).getQueryIterable();
            List<Database> databases = dbIterable.toList();
            if (databases.size() != 1) {
                throw new IOException(String.format("Database %s doesn't exist", this.dbName));
            }

            db = databases.get(0);
            QueryIterable<DocumentCollection> collIterable = client.queryCollections(db.getSelfLink(),
                    String.format("select * from root r where r.id ='%s'", this.collName), null).getQueryIterable();
            List<DocumentCollection> collections = collIterable.toList();
            if (collections.size() != 1) {
                throw new IOException(String.format("collection %s doesn't exist", this.collName));
            }

            coll = collections.get(0);
            String query = this.query.toString();
            if (query != null && !query.isEmpty()) {
                query = this.query.toString();
            } else {
                query = "select * from root";
            }

            this.documentIterator = client.queryDocuments(coll.getSelfLink(), query, null).getQueryIterator();
        } catch (Exception e) {
            throw new IOException(e);
        }

        return this.documentIterator;
    }

    public String toString() {
        return String.format("DocumentDBSplit(collection=%s)", this.collName);
    }

}