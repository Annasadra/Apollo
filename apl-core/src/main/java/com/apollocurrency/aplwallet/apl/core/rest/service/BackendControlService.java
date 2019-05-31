/*
 * Copyright © 2018-2019 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.rest.service;

import com.apollocurrency.aplwallet.api.dto.DurableTaskInfo;
import com.apollocurrency.aplwallet.api.dto.NodeHWStatusInfo;
import java.util.List;
import javax.enterprise.context.ApplicationScoped;

/**
 *
 * @author alukin@gmail.com
 */
@ApplicationScoped
public class BackendControlService {
    
    public NodeHWStatusInfo getHWStatus(){
        NodeHWStatusInfo res = new NodeHWStatusInfo();
        res.cpuCores = 4;
        return res;
    } 

    public List<DurableTaskInfo> getNodeTasks() {
        return null;
    }
}
