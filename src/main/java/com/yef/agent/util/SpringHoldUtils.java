package com.yef.agent.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class SpringHoldUtils implements ApplicationContextAware {

    private static ApplicationContext applicationContext;


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringHoldUtils.applicationContext = applicationContext;
    }

    public static <T> T getBean(String beanId) {
        return (T) applicationContext.getBean(beanId);
    }

    /**
     * 根据 Class 类型获取 Bean
     *
     * @param clazz Bean 类型
     * @return Bean 实例
     */
    public static <T> T getBean(Class<T> clazz) {
        return applicationContext.getBean(clazz);
    }

}
