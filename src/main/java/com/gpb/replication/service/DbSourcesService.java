package com.gpb.replication.service;


import java.util.List;

import com.gpb.replication.dto.SourceDbConnections;

public interface DbSourcesService {
     List<SourceDbConnections> getDbConnections();
}
