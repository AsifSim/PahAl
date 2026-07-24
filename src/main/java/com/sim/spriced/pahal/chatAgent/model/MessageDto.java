package com.sim.spriced.pahal.chatAgent.model;

import java.time.LocalDateTime;

public record MessageDto(
    String sender,
    String thoughtChain,
    String content,
    LocalDateTime timestamp
) {}
