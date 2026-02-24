package com.yef.agent.chat;

import com.yef.agent.repository.StatusTransitionRepository;
import com.yef.agent.service.DominantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory")
public class AgentMemoryController {

    @Autowired
    private DominantService dominantService;
    @Autowired
    private StatusTransitionRepository statusTransitionRepository;

    // 🔥 当前 Dominant
    @GetMapping("/slot")
    public Object dominant(
            @RequestParam String userId,
            @RequestParam String slotKey) {

        return dominantService
                .loadDominantView(userId, slotKey)
                .orElse(null);
    }

    // 🔥 状态迁移历史
    @GetMapping("/transitions")
    public Object transitions(
            @RequestParam String userId,
            @RequestParam String slotKey) {
        return null;
       /* return statusTransitionRepository
                .listBySlot(userId, slotKey);*/
    }
}