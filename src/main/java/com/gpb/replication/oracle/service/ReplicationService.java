package com.gpb.replication.oracle.service;

public interface ReplicationService {
   void startReplicationAsync(String serviceName);
   void startReplication(String serviceName);
}
