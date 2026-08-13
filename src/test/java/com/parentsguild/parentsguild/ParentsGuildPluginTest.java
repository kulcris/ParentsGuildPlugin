package com.parentsguild.parentsguild;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ParentsGuildPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(ParentsGuildPlugin.class);
        RuneLite.main(args);
    }
}
