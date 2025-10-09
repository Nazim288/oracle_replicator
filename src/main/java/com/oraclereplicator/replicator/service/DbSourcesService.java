package com.oraclereplicator.replicator.service;


import com.oraclereplicator.replicator.dto.SourceDbConnections;

import java.util.List;

public interface DbSourcesService {
     List<SourceDbConnections> getDbConnections();
}
