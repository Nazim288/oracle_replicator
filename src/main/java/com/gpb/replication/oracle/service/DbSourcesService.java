package com.gpb.replication.oracle.service;


import java.util.List;

import com.gpb.replication.oracle.dto.SourceDbConnections;

public interface DbSourcesService {
     List<SourceDbConnections> getDbConnections();
}
