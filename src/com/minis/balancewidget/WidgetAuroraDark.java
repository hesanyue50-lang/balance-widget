package com.minis.balancewidget;

/**
 * 桌面小组件 · Aurora 暗（固定风格 #1）。
 *
 * 和 {@link BalanceWidgetProvider} 的区别只有一个：**风格固定，不跟应用主题走**。
 * 为什么单独拆一个 Provider 而不是在同一个里按主题切换：
 * 系统「添加小组件」列表是按照 Provider 列的，拆开之后用户能直接看到
 * 七种风格各自长什么样、想要哪个就加哪个 —— 比"加完再去 App 里换主题"直观得多。
 *
 * 也可以同时往桌面放好几个不同风格的，各看各的。
 */
public class WidgetAuroraDark extends BalanceWidgetProvider {

    @Override
    public int fixedStyle() {
        return 1;
    }
}
