package com.wechatai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

////////////////////////////////////////////////////////////////////
//                            _ooOoo_                             //
//                           o8888888o                            //
//                           88" . "88                            //
//                           (| ^_^ |)                            //
//                           O\  =  /O                            //
//                        ____/`---'\____                         //
//                      .'  \\|     |//  `.                       //
//                     /  \\|||  :  |||//  \                      //
//                    /  _||||| -:- |||||-  \                     //
//                    |   | \\\  -  /// |   |                     //
//                    | \_|  ''\---/''  |   |                     //
//                    \  .-\__  `-`  ___/-. /                     //
//                  ___`. .'  /--.--\  `. . ___                   //
//                ."" '<  `.___\_<|>_/___.'  >'"".                //
//              | | :  `- \`.;`\ _ /`;.`/ - ` : | |               //
//              \  \ `-.   \_ __\ /__ _/   .-` /  /               //
//        ========`-.____`-.___\_____/___.-`____.-'========       //
//                             `=---='                            //
//        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^      //
//        佛祖保佑             永无BUG              永不修改          //
////////////////////////////////////////////////////////////////////

/**
 * 应用启动入口。
 * <p>
 * 本工程为<strong>单体多模块</strong> Maven 工程：各业务模块打包进同一 JAR、同一 Spring 容器，
 * 模块间是 JVM 内方法调用，不是微服务/远程 RPC。扫描 {@code com.wechatai} 下全部组件与 Mapper。
 */
@SpringBootApplication(scanBasePackages = "com.wechatai")
@EnableScheduling
public class WechatAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WechatAiApplication.class, args);
    }

//    @Bean
//    CommandLineRunner testSend(WechatService wechatService) {
//        return args -> {
//            WechatSendReq req = new WechatSendReq();
//            req.setToUser("o9cq80wXHbhl31gJMKhgq3PdIq7g@im.wechat");   // ← 填你的微信号对应的 wxid
//            req.setContent("你好，我是AI助手");
//            WechatSendResp resp = wechatService.sendCustomerMessage(req);
//            System.out.println("发送结果: " + resp.getStatus());
//        };
//    }
}
