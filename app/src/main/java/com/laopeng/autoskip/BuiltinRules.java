package com.laopeng.autoskip;

/**
 * 出厂内置规则。**本文件由 tools/gen_builtin.py 从 tools/builtin_rules.json 生成，
 * 不要手改** —— 要改规则请改 JSON 再跑一次生成器。
 *
 * <p>这层永远参与匹配（见 {@link RuleStore}），所以它同时也是「订阅拉不到 /
 * 订阅里没有这个应用 / 上游把规则默认关闭」时的兜底。
 *
 * <p>当前：3 条通用规则（packageName="*"）+ 7 个应用专属组，共 10 条。
 */
public class BuiltinRules {

    public static final String JSON =
        "{\n" +
        "  \"version\": 2,\n" +
        "  \"apps\": [\n" +
        "    {\n" +
        "      \"id\": -1,\n" +
        "      \"name\": \"通用（所有应用）\",\n" +
        "      \"packageName\": \"*\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"跳过开屏广告\",\n" +
        "          \"matchType\": \"any\",\n" +
        "          \"matches\": [\n" +
        "            \"跳过\",\n" +
        "            \"跳過\",\n" +
        "            \"Skip\",\n" +
        "            \"跳过广告\",\n" +
        "            \"点击跳过\",\n" +
        "            \"跳过 >\",\n" +
        "            \"跳过>\"\n" +
        "          ],\n" +
        "          \"excludes\": [\n" +
        "            \"跳过引导\",\n" +
        "            \"跳过教程\",\n" +
        "            \"跳过设置\",\n" +
        "            \"跳过此步骤\",\n" +
        "            \"不跳过\",\n" +
        "            \"跳过此步\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 2,\n" +
        "          \"cooldown\": 1500\n" +
        "        },\n" +
        "        {\n" +
        "          \"name\": \"关闭广告弹窗\",\n" +
        "          \"matchType\": \"desc\",\n" +
        "          \"matches\": [\n" +
        "            \"关闭广告\",\n" +
        "            \"关闭弹窗\",\n" +
        "            \"关闭推广\",\n" +
        "            \"Close ad\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 1,\n" +
        "          \"cooldown\": 2000\n" +
        "        },\n" +
        "        {\n" +
        "          \"name\": \"跳过按钮-无文字图标\",\n" +
        "          \"matchType\": \"id\",\n" +
        "          \"regex\": true,\n" +
        "          \"matches\": [\n" +
        "            \"/ms_skipView$\",\n" +
        "            \"/ms_skipView_container$\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 3,\n" +
        "          \"cooldown\": 800\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": 101,\n" +
        "      \"name\": \"铁路12306\",\n" +
        "      \"packageName\": \"com.MobileTicket\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"开屏广告-跳过按钮\",\n" +
        "          \"matchType\": \"id\",\n" +
        "          \"regex\": true,\n" +
        "          \"matches\": [\n" +
        "            \"/tv_main_splash_skip$\",\n" +
        "            \"/tv_skip$\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 2,\n" +
        "          \"cooldown\": 800\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": 102,\n" +
        "      \"name\": \"小米钱包\",\n" +
        "      \"packageName\": \"com.mipay.wallet\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"开屏广告-跳过按钮\",\n" +
        "          \"matchType\": \"id\",\n" +
        "          \"regex\": true,\n" +
        "          \"matches\": [\n" +
        "            \"/skip$\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 1,\n" +
        "          \"cooldown\": 1200\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": 103,\n" +
        "      \"name\": \"HMS Core\",\n" +
        "      \"packageName\": \"com.huawei.hwid\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"全屏广告-关闭按钮\",\n" +
        "          \"matchType\": \"id\",\n" +
        "          \"regex\": true,\n" +
        "          \"matches\": [\n" +
        "            \"/interstitial_close$\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 1,\n" +
        "          \"cooldown\": 1500\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": 104,\n" +
        "      \"name\": \"网上国网\",\n" +
        "      \"packageName\": \"com.sgcc.wsgw.cn\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"弹窗广告-关闭\",\n" +
        "          \"matchType\": \"id\",\n" +
        "          \"regex\": true,\n" +
        "          \"matches\": [\n" +
        "            \"/btn_remind_close$\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 1,\n" +
        "          \"cooldown\": 1500\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": 105,\n" +
        "      \"name\": \"有道云笔记\",\n" +
        "      \"packageName\": \"com.youdao.note\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"卡片广告-关闭\",\n" +
        "          \"matchType\": \"id\",\n" +
        "          \"regex\": true,\n" +
        "          \"matches\": [\n" +
        "            \"/close_ad$\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 1,\n" +
        "          \"cooldown\": 1500\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": 106,\n" +
        "      \"name\": \"红果免费短剧\",\n" +
        "      \"packageName\": \"com.phoenix.read\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"关闭广告\",\n" +
        "          \"matchType\": \"text\",\n" +
        "          \"matches\": [\n" +
        "            \"关闭此广告\",\n" +
        "            \"点击关闭广告并退出小说\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 2,\n" +
        "          \"cooldown\": 1200\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": 107,\n" +
        "      \"name\": \"转转\",\n" +
        "      \"packageName\": \"com.wuba.zhuanzhuan\",\n" +
        "      \"rules\": [\n" +
        "        {\n" +
        "          \"name\": \"拒绝升级/评价提示\",\n" +
        "          \"matchType\": \"text\",\n" +
        "          \"matches\": [\n" +
        "            \"下次再说\",\n" +
        "            \"残忍拒绝\"\n" +
        "          ],\n" +
        "          \"action\": \"click\",\n" +
        "          \"maxClick\": 1,\n" +
        "          \"cooldown\": 2000\n" +
        "        }\n" +
        "      ]\n" +
        "    }\n" +
        "  ]\n" +
        "}";
}
