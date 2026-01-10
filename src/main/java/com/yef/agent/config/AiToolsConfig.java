package com.yef.agent.config;

import com.yef.agent.service.InsuranceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import java.util.function.Function;


@Configuration
public class AiToolsConfig {
    @Bean
    @Description("根据保险订单号查询订单状态") // AI 通过这个描述来决定是否调用该函数
    public Function<OrderRequest, OrderResponse> insuranceOrderTool(InsuranceService service) {
        return request -> new OrderResponse(service.getOrderStatus(request.orderId()));
    }





}
record OrderRequest(String orderId) {}
record OrderResponse(String status) {}

