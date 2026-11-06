package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class SelectorEngineTest {
    @Test
    public void matchesClassAndAttributes() {
        FakeNode root = new FakeNode("android.widget.FrameLayout");
        FakeNode skip = root.add(new FakeNode("android.widget.TextView")
                .text("5 秒 跳过")
                .visible(true));

        SelectorEngine.Selector selector = SelectorEngine.compile(
                "TextView[text*=\"跳过\"][text.length<10][visibleToUser=true]"
        );

        assertEquals(skip, selector.findFirst(root));
    }

    @Test
    public void matchesDirectParentAndAncestorRelations() {
        FakeNode root = new FakeNode("android.widget.FrameLayout");
        FakeNode container = root.add(new FakeNode("android.widget.LinearLayout"));
        FakeNode skip = container.add(new FakeNode("android.widget.TextView")
                .text("跳过")
                .visible(true));

        assertEquals(skip, SelectorEngine.compile(
                "LinearLayout > TextView[text=\"跳过\"]"
        ).findFirst(root));
        assertEquals(skip, SelectorEngine.compile(
                "FrameLayout >n TextView[text=\"跳过\"]"
        ).findFirst(root));
        assertNull(SelectorEngine.compile(
                "FrameLayout > TextView[text=\"跳过\"]"
        ).findFirst(root));
    }

    @Test
    public void matchesPreviousSiblingAdEvidence() {
        FakeNode root = new FakeNode("android.widget.FrameLayout");
        root.add(new FakeNode("android.widget.TextView").text("广告").visible(true));
        FakeNode close = root.add(new FakeNode("android.widget.ImageView")
                .vid("ad_close")
                .visible(true));

        assertEquals(close, SelectorEngine.compile(
                "*[text*=\"广告\"] +n *[vid*=\"close\"][visibleToUser=true]"
        ).findFirst(root));
    }

    @Test
    public void supportsTargetMarkerBeforeDescendantRelation() {
        FakeNode root = new FakeNode("android.widget.FrameLayout");
        FakeNode ad = root.add(new FakeNode("android.widget.TextView").text("广告"));

        assertEquals(ad, SelectorEngine.compile(
                "@[text=\"广告\"] <<n FrameLayout"
        ).findFirst(root));
    }

    @Test
    public void supportsOrConditions() {
        FakeNode root = new FakeNode("android.widget.FrameLayout");
        FakeNode close = root.add(new FakeNode("android.widget.TextView")
                .text("关闭")
                .visible(true));

        assertEquals(close, SelectorEngine.compile(
                "TextView[text=\"跳过\"||text=\"关闭\"]"
        ).findFirst(root));
    }

    @Test
    public void rejectsUnsupportedComputedValues() {
        assertNotNull(SelectorEngine.compile("TextView[text=\"跳过\"]"));
        assertThrows(IllegalArgumentException.class,
                () -> SelectorEngine.compile("TextView[text=parent.text]"));
        assertThrows(IllegalArgumentException.class,
                () -> SelectorEngine.compile("TextView["));
    }

    private static final class FakeNode implements SelectorEngine.Node {
        private final String name;
        private final List<FakeNode> children = new ArrayList<>();
        private FakeNode parent;
        private String id = "";
        private String vid = "";
        private String text = "";
        private String desc = "";
        private boolean clickable;
        private boolean visible;

        FakeNode(String name) {
            this.name = name;
        }

        FakeNode id(String value) {
            id = value;
            return this;
        }

        FakeNode vid(String value) {
            vid = value;
            return this;
        }

        FakeNode text(String value) {
            text = value;
            return this;
        }

        FakeNode desc(String value) {
            desc = value;
            return this;
        }

        FakeNode clickable(boolean value) {
            clickable = value;
            return this;
        }

        FakeNode visible(boolean value) {
            visible = value;
            return this;
        }

        FakeNode add(FakeNode child) {
            child.parent = this;
            children.add(child);
            return child;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String vid() {
            return vid;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public String desc() {
            return desc;
        }

        @Override
        public boolean clickable() {
            return clickable;
        }

        @Override
        public boolean focusable() {
            return false;
        }

        @Override
        public boolean checkable() {
            return false;
        }

        @Override
        public boolean checked() {
            return false;
        }

        @Override
        public boolean editable() {
            return false;
        }

        @Override
        public boolean longClickable() {
            return false;
        }

        @Override
        public boolean visibleToUser() {
            return visible;
        }

        @Override
        public int left() {
            return 0;
        }

        @Override
        public int top() {
            return 0;
        }

        @Override
        public int right() {
            return 1;
        }

        @Override
        public int bottom() {
            return 1;
        }

        @Override
        public int width() {
            return 1;
        }

        @Override
        public int height() {
            return 1;
        }

        @Override
        public int childCount() {
            return children.size();
        }

        @Override
        public int index() {
            return parent == null ? 0 : parent.children.indexOf(this);
        }

        @Override
        public int depth() {
            return parent == null ? 0 : parent.depth() + 1;
        }

        @Override
        public SelectorEngine.Node parent() {
            return parent;
        }

        @Override
        public SelectorEngine.Node child(int index) {
            return children.get(index);
        }
    }
}
