package com.wechatai.wechat.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 媒体文件描述符 — 图片/文件下载后的元信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WechatMediaDescriptor {
    private String fileName;
    private String fileType;
    private long fileSize;
}
