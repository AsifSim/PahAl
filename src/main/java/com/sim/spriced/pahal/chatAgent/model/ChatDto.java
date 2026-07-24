package com.sim.spriced.pahal.chatAgent.model;

import java.util.List;

public record ChatDto(
    String chatId,
    String sessionId,
    String chatTitle,
    String summary,
    List<MessageDto> messages
) {}
