package com.yef.agent.service;

import com.yef.agent.vo.StudentResponse;
import org.springframework.stereotype.Service;


@Service
public class InsuranceService {
    // 模拟数据库查询
    public String getOrderStatus(String policyNo) {
        if ("YF1992".equals(policyNo)) {
            return "保单号 " + policyNo + "：状态【保障中】，承保人为：叶丰。";
        } else {
            return "未查询到保单 " + policyNo + "，请核对单号是否正确。";
        }
    }

    public StudentResponse getStudentResponse() {
        StudentResponse studentResponse = new StudentResponse(1,"yefeng","111@gmail.com");
        return studentResponse;
    }

}
