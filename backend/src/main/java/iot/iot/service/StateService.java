package iot.iot.service;

import com.fasterxml.jackson.databind.JsonNode;
import iot.iot.integration.nodered.NodeRedClient;
import iot.iot.integration.nodered.NodeRedStateMapper;
import org.springframework.stereotype.Service;

@Service
public class StateService {
    private final NodeRedClient nodeRed;
    private final NodeRedStateMapper mapper;
    public StateService(NodeRedClient nodeRed, NodeRedStateMapper mapper) { this.nodeRed = nodeRed; this.mapper = mapper; }
    public JsonNode getState() { return mapper.toApi(mapper.snapshot(nodeRed.state())); }
}
