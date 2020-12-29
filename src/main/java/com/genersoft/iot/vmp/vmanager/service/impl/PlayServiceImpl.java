package com.genersoft.iot.vmp.vmanager.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.MediaServerConfig;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorager;
import com.genersoft.iot.vmp.vmanager.service.IPlayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PlayServiceImpl implements IPlayService {

    private final static Logger logger = LoggerFactory.getLogger(PlayServiceImpl.class);

    @Autowired
    private IVideoManagerStorager storager;

    @Autowired
    private DeferredResultHolder resultHolder;

    @Value("${media.internetIp}")
    private String internetIp;

    @Value("${media.internetPort}")
    private String internetPort;

    @Override
    public void onPublishHandlerForPlay(JSONObject resonse, String deviceId, String channelId, String uuid) {
        RequestMessage msg = new RequestMessage();
        msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
        StreamInfo streamInfo = onPublishHandler(resonse, deviceId, channelId, uuid);
        if (streamInfo != null) {
            storager.startPlay(streamInfo);
            msg.setData(JSON.toJSONString(streamInfo));
            resultHolder.invokeResult(msg);
        } else {
            logger.warn("设备预览API调用失败！");
            msg.setData("设备预览API调用失败！");
            resultHolder.invokeResult(msg);
        }
    }

    @Override
    public void onPublishHandlerForPlayBack(JSONObject resonse, String deviceId, String channelId, String uuid) {
        RequestMessage msg = new RequestMessage();
        msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
        StreamInfo streamInfo = onPublishHandler(resonse, deviceId, channelId, uuid);
        if (streamInfo != null) {
            storager.startPlayback(streamInfo);
            msg.setData(JSON.toJSONString(streamInfo));
            resultHolder.invokeResult(msg);
        } else {
            logger.warn("设备预览API调用失败！");
            msg.setData("设备预览API调用失败！");
            resultHolder.invokeResult(msg);
        }
    }

    public StreamInfo onPublishHandler(JSONObject resonse, String deviceId, String channelId, String uuid) {
        String streamId = resonse.getString("id");
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setStreamId(streamId);
        streamInfo.setDeviceID(deviceId);
        streamInfo.setChannelId(channelId);
        MediaServerConfig mediaServerConfig = storager.getMediaInfo();

        if (internetIp != null) {
            streamInfo.setFlv(String.format("https://%s:%s/rtp/%s.flv", internetIp, internetPort, streamId));
            streamInfo.setWs_flv(String.format("wss://%s:%s/rtp/%s.flv", internetIp, internetPort, streamId));
        } else {
            streamInfo.setFlv(String.format("http://%s:%s/rtp/%s.flv", mediaServerConfig.getWanIp(), mediaServerConfig.getHttpPort(), streamId));
            streamInfo.setWs_flv(String.format("ws://%s:%s/rtp/%s.flv", mediaServerConfig.getWanIp(), mediaServerConfig.getHttpPort(), streamId));
        }

        return streamInfo;
    }

}
