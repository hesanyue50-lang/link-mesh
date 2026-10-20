package com.jiuyue.lanmesh.ui;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * 扫码页。
 *
 * 继承库里的 CaptureActivity，只是换一个暗色主题（AppTheme 的 DayNight 变体），
 * 避免默认的亮色界面与主界面割裂。相机预览由库自己管理，minSdk 19 可用。
 */
public class QrScanActivity extends CaptureActivity {
}
