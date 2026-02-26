package com.yef.agent.memory.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentResponse {

    private String explain;

    private Object graph;   // 先用 Object，后面再强类型

}