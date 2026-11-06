package com.dshwatch.ui.html

/**
 * 把一段 HTML 包成可渲染的文档。
 *
 * 两种用法共用同一套基础样式，区别只在于「给不给自动量高」：
 *  - 全屏（[HtmlScreen]）不需要量高，视口就是屏幕；
 *  - 内嵌（[InlineHtml]）需要，量出来的高度决定这条消息占多高。
 *
 * 基础样式只定三件事：清掉默认边距、把字号拉到手表能看的大小、
 * 让图片/表格不撑破宽度。**不去覆盖颜色和圆角** —— 界面长什么样由作者
 * 的 CSS 决定，这正是「自定义界面」的意义；如果连背景色都被我们统一掉，
 * 那它就不叫自定义了。
 */
internal object HtmlShell {

    /** ARGB int → CSS 十六进制色值 */
    fun hex(argb: Int): String = "#%06X".format(0xFFFFFF and argb)

    /**
     * @param inline 内嵌模式：注入量高脚本 + 去掉滚动条
     * @param pickable 选项可点。只有「最新一条消息」才为 true ——
     *   历史消息里的 A/B/C/D 点下去会往对话里插一条过期的回复。
     */
    fun wrap(
        html: String,
        fg: String,
        accent: String,
        inline: Boolean,
        pickable: Boolean = false,
    ): String {
        // 作者已经写了完整文档：不再套壳，只补一个 viewport（手表屏幕窄，
        // 没有这个 meta 的话 980px 的虚拟视口会把整页缩成一条）
        val body = if (html.contains("<html", ignoreCase = true)) {
            injectViewport(html)
        } else {
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">" +
                "<style>${BASE_CSS.replace("%%FG%%", fg).replace("%%ACCENT%%", accent)}</style>" +
                "</head><body>$html</body></html>"
        }
        val withPick = if (pickable) injectPick(body) else injectPickLocked(body)
        return if (inline) injectMeasure(withPick) else withPick
    }

    /**
     * 选项点击脚本。
     *
     * 约定用 **`data-pick` 属性**而不是要求作者写 onclick：
     * 模型写 `onclick="dshWatch.pick('...')"` 时很容易在引号、转义上翻车，
     * 而属性值是纯文本，写错也只是文案不对，不会让整块界面变成一片死的。
     *
     *   <button data-pick="A. 走过去看看">A. 走过去看看</button>
     *
     * 点中之后自己把整组禁用掉：手表的触点小，很容易连点两下，
     * 而 `pick` 每次都会真的发一条消息 —— 不去重就是连发两条一样的回复。
     */
    private fun injectPick(html: String): String {
        val script = """
            <script>
            (function(){
              function hitOf(el){
                while(el && el.getAttribute){
                  if(el.getAttribute('data-pick')!==null) return el;
                  el = el.parentNode;
                }
                return null;
              }
              document.addEventListener('click', function(e){
                var el = hitOf(e.target);
                if(!el || el.getAttribute('data-picked')==='1') return;
                var v = el.getAttribute('data-pick');
                if(!v) v = (el.innerText||'').trim();
                if(!v) return;
                var all = document.querySelectorAll('[data-pick]');
                for(var i=0;i<all.length;i++){
                  all[i].setAttribute('data-picked','1');
                  all[i].style.pointerEvents='none';
                  all[i].style.opacity='0.45';
                  all[i].style.filter='grayscale(1)';
                }
                el.setAttribute('data-picked','2');
                el.style.opacity='1';
                el.style.filter='none';
                el.style.outline='2px solid currentColor';
                if(window.dshWatch && window.dshWatch.pick) window.dshWatch.pick(v);
              }, true);
            })();
            </script>
        """.trimIndent()
        return appendBeforeBody(html, script)
    }

    /** 历史面板：选项画成灰的、不可点，避免"点了没反应"的错觉 */
    private fun injectPickLocked(html: String): String {
        val css = """
            <style>
            [data-pick]{pointer-events:none !important;opacity:.45;filter:grayscale(.8);}
            </style>
        """.trimIndent()
        return appendBeforeBody(html, css)
    }

    private fun appendBeforeBody(html: String, extra: String): String =
        if (html.contains("</body>", ignoreCase = true)) {
            html.replace(Regex("(?i)</body>"), extra + "</body>")
        } else {
            html + extra
        }

    private val BASE_CSS = """
        html,body{margin:0;padding:0;background:transparent;color:%%FG%%;
          font:13px/1.55 -apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif;
          -webkit-text-size-adjust:100%;word-wrap:break-word;overflow-x:hidden;}
        body{padding:8px;}
        img,video,svg,table{max-width:100%;height:auto;}
        table{border-collapse:collapse;}
        a{color:%%ACCENT%%;}
        p{margin:.4em 0;}
        h1,h2,h3{margin:.5em 0 .3em;font-size:1.05em;}
        ::-webkit-scrollbar{width:0;height:0;}
    """.trimIndent()

    private fun injectViewport(html: String): String {
        if (html.contains("name=\"viewport\"", ignoreCase = true)) return html
        val head = Regex("(?i)<head[^>]*>")
        if (head.containsMatchIn(html)) {
            return head.replaceFirst(html, "$0<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">")
        }
        return html
    }

    /**
     * 注入量高脚本。
     *
     * 光在 `onPageFinished` 时量一次不够：界面里的折叠面板展开、图片加载完、
     * 切换标签页都会改变高度，WebView 自己不会告诉我们。所以挂一个
     * MutationObserver，DOM 一变就回报一次。
     *
     * 回报走 `window.dshWatch.resize`，由原生侧的 [InlineHtml] 接住。
     * 接口只暴露这一个方法，且只接受一个 int —— 即使内容里有恶意脚本，
     * 也够不到应用的其他东西。
     */
    private fun injectMeasure(html: String): String {
        val script = """
            <script>
            (function(){
              var last=-1, pending=false;
              function measure(){
                pending=false;
                var h=Math.max(
                  document.documentElement.scrollHeight,
                  document.body?document.body.scrollHeight:0
                );
                if(h!==last){last=h; if(window.dshWatch) window.dshWatch.resize(h);}
              }
              function schedule(){ if(pending)return; pending=true; requestAnimationFrame(measure); }
              window.addEventListener('load', schedule);
              window.addEventListener('resize', schedule);
              document.addEventListener('DOMContentLoaded', schedule);
              try{
                new MutationObserver(schedule).observe(document.documentElement,
                  {childList:true,subtree:true,attributes:true,characterData:true});
              }catch(e){}
              // 图片是异步的：加载完再量一次，否则高度会停在占位状态
              document.addEventListener('load', function(e){
                if(e.target && e.target.tagName) schedule();
              }, true);
              setTimeout(schedule, 50);
              setTimeout(schedule, 300);
            })();
            </script>
        """.trimIndent()
        return if (html.contains("</body>", ignoreCase = true)) {
            html.replace(Regex("(?i)</body>"), "$script</body>")
        } else {
            html + script
        }
    }
}
