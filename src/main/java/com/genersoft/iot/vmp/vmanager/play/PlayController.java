package com.genersoft.iot.vmp.vmanager.play;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.MediaServerConfig;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.storager.IVideoManagerStorager;
import com.genersoft.iot.vmp.vmanager.service.IPlayService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import javax.sip.message.Response;
import java.util.UUID;

@CrossOrigin
@RestController
@RequestMapping("/api")
public class PlayController {

    private final static Logger logger = LoggerFactory.getLogger(PlayController.class);

    @Autowired
    private SIPCommander cmder;

    @Autowired
    private IVideoManagerStorager storager;

    @Autowired
    private ZLMRESTfulUtils zlmresTfulUtils;

    @Autowired
    private DeferredResultHolder resultHolder;

    @Autowired
    private IPlayService playService;

    @Value("${media.internetIp}")
    private String internetIp;

    @Value("${media.internetPort}")
    private String internetPort;

    @GetMapping("/play/{deviceId}/{channelId}")
    public DeferredResult<ResponseEntity<String>> play(@PathVariable String deviceId,
                                                       @PathVariable String channelId) {


        Device device = storager.queryVideoDevice(deviceId);
        StreamInfo streamInfo = storager.queryPlayByDevice(deviceId, channelId);

        UUID uuid = UUID.randomUUID();
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>();

        // 录像查询以channelId作为deviceId查询
        resultHolder.put(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid, result);

        if (streamInfo == null) {
            // 发送点播消息
            cmder.playStreamCmd(device, channelId, (JSONObject response) -> {
                logger.info("收到订阅消息： " + response.toJSONString());
                playService.onPublishHandlerForPlay(response, deviceId, channelId, uuid.toString());
            }, event -> {
                RequestMessage msg = new RequestMessage();
                msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
                Response response = event.getResponse();
                msg.setData(String.format("点播失败， 错误码： %s, %s", response.getStatusCode(), response.getReasonPhrase()));
                resultHolder.invokeResult(msg);
            });
        } else {
            String streamId = streamInfo.getStreamId();
            JSONObject rtpInfo = zlmresTfulUtils.getRtpInfo(streamId);
            if (rtpInfo.getBoolean("exist")) {
                RequestMessage msg = new RequestMessage();
                msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
                msg.setData(JSON.toJSONString(streamInfo));
                resultHolder.invokeResult(msg);
            } else {
                storager.stopPlay(streamInfo);
                cmder.playStreamCmd(device, channelId, (JSONObject response) -> {
                    logger.info("收到订阅消息： " + response.toJSONString());
                    playService.onPublishHandlerForPlay(response, deviceId, channelId, uuid.toString());
                }, event -> {
                    RequestMessage msg = new RequestMessage();
                    msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
                    Response response = event.getResponse();
                    msg.setData(String.format("点播失败， 错误码： %s, %s", response.getStatusCode(), response.getReasonPhrase()));
                    resultHolder.invokeResult(msg);
                });
            }
        }

        // 超时处理
        result.onTimeout(() -> {
            logger.warn(String.format("设备点播超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            // 释放rtpserver
            cmder.closeRTPServer(device, channelId);
            RequestMessage msg = new RequestMessage();
            msg.setId(DeferredResultHolder.CALLBACK_CMD_PlAY + uuid);
            msg.setData("Timeout");
            resultHolder.invokeResult(msg);
        });
        return result;
    }

    @PostMapping("/play/{streamId}/stop")
    public ResponseEntity<String> playStop(@PathVariable String streamId) {

        cmder.streamByeCmd(streamId);
        StreamInfo streamInfo = storager.queryPlayByStreamId(streamId);
        if (streamInfo == null)
            return new ResponseEntity<String>("streamId not found", HttpStatus.OK);
        storager.stopPlay(streamInfo);
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("设备预览停止API调用，streamId：%s", streamId));
        }

        if (streamId != null) {
            JSONObject json = new JSONObject();
            json.put("streamId", streamId);
            return new ResponseEntity<String>(json.toString(), HttpStatus.OK);
        } else {
            logger.warn("设备预览停止API调用失败！");
            return new ResponseEntity<String>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 将不是h264的视频通过ffmpeg 转码为h264 + aac
     *
     * @param streamId 流ID
     * @return
     */
    @PostMapping("/play/{streamId}/convert")
    public ResponseEntity<String> playConvert(@PathVariable String streamId) {
        StreamInfo streamInfo = storager.queryPlayByStreamId(streamId);
        if (streamInfo == null) {
            logger.warn("视频转码API调用失败！, 视频流已经停止!");
            return new ResponseEntity<String>("未找到视频流信息, 视频流可能已经停止", HttpStatus.OK);
        }
        JSONObject rtpInfo = zlmresTfulUtils.getRtpInfo(streamId);
        if (!rtpInfo.getBoolean("exist")) {
            logger.warn("视频转码API调用失败！, 视频流已停止推流!");
            return new ResponseEntity<String>("推流信息在流媒体中不存在, 视频流可能已停止推流", HttpStatus.OK);
        } else {
            MediaServerConfig mediaInfo = storager.getMediaInfo();
            String dstUrl = String.format("rtmp://%s:%s/convert/%s", "127.0.0.1", mediaInfo.getRtmpPort(),
                    streamId);
            String srcUrl = String.format("rtsp://%s:%s/rtp/%s", "127.0.0.1", mediaInfo.getRtspPort(), streamId);
            JSONObject jsonObject = zlmresTfulUtils.addFFmpegSource(srcUrl, dstUrl, "1000000");
            System.out.println(jsonObject);
            JSONObject result = new JSONObject();
            if (jsonObject != null && jsonObject.getInteger("code") == 0) {
                result.put("code", 0);
                JSONObject data = jsonObject.getJSONObject("data");
                if (data != null) {
                    result.put("key", data.getString("key"));
                    StreamInfo streamInfoResult = new StreamInfo();
                    streamInfoResult.setStreamId(streamId);
                    if (StringUtils.isNotBlank(internetIp)) {
                        streamInfoResult.setFlv(String.format("https://%s:%s/convert/%s.flv", internetIp, internetPort, streamId));
                        streamInfoResult.setFlv(String.format("wss://%s:%s/convert/%s.flv", internetIp, internetPort, streamId));
                    } else {
                        streamInfoResult.setFlv(String.format("http://%s:%s/convert/%s.flv", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
                        streamInfoResult.setWs_flv(String.format("ws://%s:%s/convert/%s.flv", mediaInfo.getWanIp(), mediaInfo.getHttpPort(), streamId));
                    }
                    result.put("data", streamInfoResult);
                }
            } else {
                result.put("code", 1);
                result.put("msg", "cover fail");
            }
            return new ResponseEntity<String>(result.toJSONString(), HttpStatus.OK);
        }
    }

    /**
     * 结束转码
     *
     * @param key
     * @return
     */
    @PostMapping("/play/convert/stop/{key}")
    public ResponseEntity<String> playConvertStop(@PathVariable String key) {

        JSONObject jsonObject = zlmresTfulUtils.delFFmpegSource(key);
        System.out.println(jsonObject);
        JSONObject result = new JSONObject();
        if (jsonObject != null && jsonObject.getInteger("code") == 0) {
            result.put("code", 0);
            JSONObject data = jsonObject.getJSONObject("data");
            if (data != null && data.getBoolean("flag")) {
                result.put("code", "0");
                result.put("msg", "success");
            } else {

            }
        } else {
            result.put("code", 1);
            result.put("msg", "delFFmpegSource fail");
        }
        return new ResponseEntity<String>(result.toJSONString(), HttpStatus.OK);
    }
}

