package com.itways.assistant.journey.engine.model;

import java.util.List;

public final class ChannelVariableSchema {

    private ChannelVariableSchema() {
    }

    public static List<ChannelVariableGroup> groups() {
        return List.of(
                ChannelVariableGroup.builder()
                        .id("channelCommon")
                        .label("Channel")
                        .fields(List.of(
                                OutputField.of("channel.id", "Channel ID", "string"),
                                OutputField.of("channel.label", "Label", "string"),
                                OutputField.of("channel.username", "Username", "string"),
                                OutputField.of("channel.type", "Type", "string"),
                                OutputField.of("channel.status", "Status", "string")))
                        .build(),
                ChannelVariableGroup.builder()
                        .id("userCommon")
                        .label("Channel User")
                        .fields(List.of(
                                OutputField.of("channel.user.displayName", "Display Name", "string"),
                                OutputField.of("channel.user.id", "User ID", "string"),
                                OutputField.of("channel.user.chatId", "Chat ID", "string"),
                                OutputField.of("channel.user.platform", "Platform", "string")))
                        .build(),
                ChannelVariableGroup.builder()
                        .id("whatsappUser")
                        .label("WhatsApp User")
                        .platforms(List.of("WHATSAPP_TWILIO"))
                        .fields(List.of(
                                OutputField.of("channel.user.phone", "Phone", "string")))
                        .build(),
                ChannelVariableGroup.builder()
                        .id("telegramUser")
                        .label("Telegram User")
                        .platforms(List.of("TELEGRAM"))
                        .fields(List.of(
                                OutputField.of("channel.user.username", "Username", "string"),
                                OutputField.of("channel.user.firstName", "First Name", "string"),
                                OutputField.of("channel.user.lastName", "Last Name", "string"),
                                OutputField.of("channel.user.languageCode", "Language Code", "string")))
                        .build());
    }
}
