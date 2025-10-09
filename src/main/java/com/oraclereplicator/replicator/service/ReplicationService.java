package com.oraclereplicator.replicator.service;

public interface ReplicationService {
   void startReplicationAsync(String serviceName);
   void startReplication(String serviceName);
}
