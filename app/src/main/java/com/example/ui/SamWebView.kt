package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private const val SAM_OPTIMIZATION_JS = """
(function() {
  // 1. Ekran o'lchamini to'liq egallash (Full screen canvas & 100% viewport)
  function fixCanvasDimensions() {
    try {
      var canvas = document.getElementById('wallpaperCanvas');
      var w = window.innerWidth || document.documentElement.clientWidth;
      var h = window.innerHeight || document.documentElement.clientHeight;

      if (canvas && (canvas.width !== w || canvas.height !== h)) {
        canvas.width = w;
        canvas.height = h;
        canvas.style.width = '100vw';
        canvas.style.height = '100vh';
        if (typeof initParticles === 'function') {
          initParticles();
        }
      }

      document.documentElement.style.width = '100%';
      document.documentElement.style.height = '100%';
      document.documentElement.style.overflow = 'hidden';
      document.body.style.width = '100%';
      document.body.style.height = '100%';
      document.body.style.overflow = 'hidden';
    } catch(e) {
      console.log('Canvas fix error', e);
    }
  }

  fixCanvasDimensions();
  window.addEventListener('resize', fixCanvasDimensions);
  window.addEventListener('orientationchange', function() {
    setTimeout(fixCanvasDimensions, 100);
    setTimeout(fixCanvasDimensions, 400);
  });

  // 2. Soat vidjetini to'g'ridan-to'g'ri sensor va sichqoncha orqali qotmasdan erkin surish (Smooth Touch & Mouse Drag)
  var widget = document.getElementById('clockWidget');
  if (widget && !widget.__enhancedDragAttached) {
    widget.__enhancedDragAttached = true;
    widget.style.cursor = 'grab';
    widget.style.touchAction = 'none';

    var isDragging = false;
    var startX = 0, startY = 0;
    var initialLeft = 0, initialTop = 0;

    function startDrag(clientX, clientY) {
      isDragging = true;
      widget.style.cursor = 'grabbing';
      var rect = widget.getBoundingClientRect();
      widget.style.transform = 'none';
      widget.style.left = rect.left + 'px';
      widget.style.top = rect.top + 'px';
      startX = clientX;
      startY = clientY;
      initialLeft = rect.left;
      initialTop = rect.top;
    }

    function doDrag(clientX, clientY) {
      if (!isDragging) return;
      var dx = clientX - startX;
      var dy = clientY - startY;
      var newLeft = initialLeft + dx;
      var newTop = initialTop + dy;

      var maxW = (window.innerWidth || document.documentElement.clientWidth) - widget.offsetWidth;
      var maxH = (window.innerHeight || document.documentElement.clientHeight) - widget.offsetHeight;
      if (maxW > 0) newLeft = Math.max(0, Math.min(newLeft, maxW));
      if (maxH > 0) newTop = Math.max(0, Math.min(newTop, maxH));

      widget.style.left = newLeft + 'px';
      widget.style.top = newTop + 'px';
    }

    function endDrag() {
      if (isDragging) {
        isDragging = false;
        widget.style.cursor = 'grab';
      }
    }

    // Touch hodisalari (sensorli doska va mobil uchun)
    widget.addEventListener('touchstart', function(e) {
      if (e.touches && e.touches.length === 1) {
        startDrag(e.touches[0].clientX, e.touches[0].clientY);
        e.stopPropagation();
      }
    }, { passive: false });

    window.addEventListener('touchmove', function(e) {
      if (isDragging && e.touches && e.touches.length === 1) {
        doDrag(e.touches[0].clientX, e.touches[0].clientY);
        if (e.cancelable) e.preventDefault();
      }
    }, { passive: false });

    window.addEventListener('touchend', endDrag, { passive: true });
    window.addEventListener('touchcancel', endDrag, { passive: true });

    // Sichqoncha hodisalari (emulyator va sichqoncha uchun)
    widget.addEventListener('mousedown', function(e) {
      startDrag(e.clientX, e.clientY);
      e.preventDefault();
      e.stopPropagation();
    });

    window.addEventListener('mousemove', function(e) {
      if (isDragging) {
        doDrag(e.clientX, e.clientY);
        e.preventDefault();
      }
    });

    window.addEventListener('mouseup', endDrag);
  }

  // Tishli g'ildirakcha bosilganda sozlamalar panelini ochish va Android Admin tugmasini qo'shish
  var gearBtn = document.getElementById('gearBtn');
  var settingsModal = document.getElementById('settingsModal');
  if (gearBtn && settingsModal && !gearBtn.__settingsModalAttached) {
    gearBtn.__settingsModalAttached = true;

    function toggleSettings(e) {
      if (e) {
        e.stopPropagation();
      }
      settingsModal.classList.toggle('active');
    }

    gearBtn.addEventListener('click', toggleSettings);
    gearBtn.addEventListener('touchend', function(e) {
      toggleSettings(e);
    });

    // Tashqariga bosilganda sozlamalar yopilishi
    window.addEventListener('click', function(e) {
      if (!settingsModal.contains(e.target) && !gearBtn.contains(e.target)) {
        settingsModal.classList.remove('active');
      }
    });

    // Sozlamalar paneli ichiga "Admin PIN & Bot Sozlamalari" tugmasini qo'shish
    if (!document.getElementById('androidAdminBtn')) {
      var adminRow = document.createElement('div');
      adminRow.className = 'control-row';
      adminRow.style.marginTop = '14px';
      adminRow.style.borderTop = '1px solid rgba(255, 255, 255, 0.2)';
      adminRow.style.paddingTop = '12px';

      var adminBtn = document.createElement('button');
      adminBtn.id = 'androidAdminBtn';
      adminBtn.innerHTML = 'Admin PIN va Sozlamalar';
      adminBtn.style.cssText = 'background: #0284c7; border: 1px solid #38bdf8; color: #ffffff; font-weight: 600; font-size: 13px; padding: 10px; border-radius: 8px; cursor: pointer; width: 100%;';

      function onAdminTrigger(e) {
        if (e) {
          e.stopPropagation();
        }
        settingsModal.classList.remove('active');
        if (window.AndroidBridge && typeof window.AndroidBridge.openAdminSettings === 'function') {
          window.AndroidBridge.openAdminSettings();
        }
      }

      adminBtn.addEventListener('click', onAdminTrigger);
      adminBtn.addEventListener('touchend', onAdminTrigger);

      adminRow.appendChild(adminBtn);
      settingsModal.appendChild(adminRow);
    }
  }

  // 3. Zarrachalar animatsiyasini GPU uchun optimallashtirish (Qotishni yo'qotish va 60 FPS)
  var canvas = document.getElementById('wallpaperCanvas');
  var modeSelect = document.getElementById('modeSelect');
  var speedSlider = document.getElementById('speedSlider');
  var particleCountInput = document.getElementById('particleCount');

  if (canvas && typeof particles !== 'undefined') {
    var ctx = canvas.getContext('2d');

    // Mobil telefon protsessorini qizitmaslik va silliq ishlash uchun sonini 70 ga moslashtirish
    if (particleCountInput && parseInt(particleCountInput.value) > 75) {
      particleCountInput.value = '70';
      var countVal = document.getElementById('countVal');
      if (countVal) countVal.innerText = '70';
      if (typeof initParticles === 'function') {
        initParticles();
      }
    }

    // Canvas render qilishni bitta paketga yig'ish (Batched drawing)
    window.renderWallpaper = function() {
      var w = canvas.width;
      var h = canvas.height;
      ctx.fillStyle = 'rgba(3, 7, 18, 0.28)';
      ctx.fillRect(0, 0, w, h);

      var mode = modeSelect ? modeSelect.value : 'particles';
      var speed = speedSlider ? parseFloat(speedSlider.value) : 2;

      if (mode === 'particles') {
        var pLen = particles.length;

        // Barcha zarrachalarni bitta siklda chizish
        ctx.fillStyle = '#00f0ff';
        ctx.beginPath();
        for (var i = 0; i < pLen; i++) {
          var p = particles[i];
          p.x += p.vx;
          p.y += p.vy;
          if (p.x < 0 || p.x > w) p.vx *= -1;
          if (p.y < 0 || p.y > h) p.vy *= -1;
          ctx.moveTo(p.x + p.radius, p.y);
          ctx.arc(p.x, p.y, p.radius, 0, 6.2831853);
        }
        ctx.fill();

        // Chiziqlarni bitta paketda tez chizish (Math.hypot o'rniga masofa kvadrati)
        ctx.strokeStyle = 'rgba(0, 240, 255, 0.12)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        var maxDistSq = 12100; // 110 * 110
        for (var i = 0; i < pLen; i++) {
          var p1 = particles[i];
          for (var j = i + 1; j < pLen; j++) {
            var p2 = particles[j];
            var dx = p1.x - p2.x;
            var dy = p1.y - p2.y;
            var distSq = dx * dx + dy * dy;
            if (distSq < maxDistSq) {
              ctx.moveTo(p1.x, p1.y);
              ctx.lineTo(p2.x, p2.y);
            }
          }
        }
        ctx.stroke();

      } else if (mode === 'starfield') {
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        for (var i = 0; i < particles.length; i++) {
          var p = particles[i];
          p.y += p.radius * speed;
          if (p.y > h) p.y = 0;
          ctx.moveTo(p.x + p.radius, p.y);
          ctx.arc(p.x, p.y, p.radius, 0, 6.2831853);
        }
        ctx.fill();
      }

      requestAnimationFrame(renderWallpaper);
    };
  }
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SamWebView(
    url: String,
    isDesktopMode: Boolean,
    touchDragEnabled: Boolean,
    zoomScale: Float,
    onOpenAdminPin: () -> Unit = {},
    onLoadingChanged: (Boolean) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onErrorOccurred: (String?) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setLayerType(View.LAYER_TYPE_NONE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF030712.toInt())

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = isDesktopMode
                loadWithOverviewMode = isDesktopMode
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun openAdminSettings() {
                        post {
                            onOpenAdminPin()
                        }
                    }
                },
                "AndroidBridge"
            )
        }
    }

    LaunchedEffect(webView) {
        onWebViewCreated(webView)
    }

    // Dynamic viewport mode update if desktop mode changes
    LaunchedEffect(isDesktopMode) {
        webView.settings.useWideViewPort = isDesktopMode
        webView.settings.loadWithOverviewMode = isDesktopMode
    }

    LaunchedEffect(url) {
        val target = if (url.isBlank()) "file:///android_asset/wallpaper/index.html" else url
        webView.loadUrl(target)
    }

    DisposableEffect(Unit) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgressChanged(newProgress)
                if (newProgress >= 100) {
                    onLoadingChanged(false)
                    // Inject optimization script as soon as loaded
                    view?.evaluateJavascript(SAM_OPTIMIZATION_JS, null)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onLoadingChanged(true)
                onErrorOccurred(null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onLoadingChanged(false)
                // Execute optimizations for full screen canvas and smooth dragging
                view?.evaluateJavascript(SAM_OPTIMIZATION_JS, null)
                // Re-check after layout stabilization
                view?.postDelayed({
                    view?.evaluateJavascript(SAM_OPTIMIZATION_JS, null)
                }, 300)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                view?.loadUrl("file:///android_asset/wallpaper/index.html")
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    onErrorOccurred(error?.description?.toString() ?: "Tarmoq xatosi yuz berdi")
                    // Tarmoq bo'lmasa yoki xato bersa, avtomatik ilova ichidagi offline jonli fon yuklanadi
                    view?.loadUrl("file:///android_asset/wallpaper/index.html")
                }
            }
        }

        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize()
    )
}
