package com.jingjump.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Adapter that exposes an {@link AccessibilityNodeInfo} tree to the selector
 * engine.
 *
 * <p>1.9.0 memoizes every property on the wrapper. The 13 built-in selectors
 * run one after another over the same flattened node list, and each selector
 * used to re-ask the framework for the class name, text, description, bounds
 * and visibility of every node — that is several thousand IPC round trips per
 * scan, several times per second on the sensitive levels. A wrapper lives for
 * exactly one scan, so caching is safe: the node it wraps is not recycled and
 * the screen does not change shape inside one scan.</p>
 */
final class AccessibilitySelectorNode implements SelectorEngine.Node {
    private final AccessibilityNodeInfo node;
    private final Map<AccessibilityNodeInfo, AccessibilitySelectorNode> cache;

    private String name;
    private String id;
    private String vid;
    private String text;
    private String desc;
    private Rect bounds;
    private int childCount = -1;
    private int index = -1;
    private int depth = -1;
    private boolean visibleToUser;
    private boolean visibleLoaded;
    private boolean clickable;
    private boolean clickableLoaded;
    private boolean focusable;
    private boolean focusableLoaded;
    private boolean checkable;
    private boolean checkableLoaded;
    private boolean checked;
    private boolean checkedLoaded;
    private boolean editable;
    private boolean editableLoaded;
    private boolean longClickable;
    private boolean longClickableLoaded;

    private AccessibilitySelectorNode(
            AccessibilityNodeInfo node,
            Map<AccessibilityNodeInfo, AccessibilitySelectorNode> cache
    ) {
        this.node = node;
        this.cache = cache;
    }

    static AccessibilitySelectorNode root(AccessibilityNodeInfo node) {
        return new AccessibilitySelectorNode(node, new IdentityHashMap<>());
    }

    AccessibilityNodeInfo original() {
        return node;
    }

    @Override
    public String name() {
        if (name == null) {
            try {
                CharSequence value = node.getClassName();
                name = value == null ? "" : value.toString();
            } catch (Throwable ignored) {
                name = "";
            }
        }
        return name;
    }

    @Override
    public String id() {
        if (id == null) {
            try {
                CharSequence value = node.getViewIdResourceName();
                id = value == null ? "" : value.toString();
            } catch (Throwable ignored) {
                id = "";
            }
        }
        return id;
    }

    @Override
    public String vid() {
        if (vid == null) {
            String value = id();
            int slash = value.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < value.length()) {
                vid = value.substring(slash + 1);
            } else {
                int separator = value.lastIndexOf(":id/");
                vid = separator >= 0 ? value.substring(separator + 4) : value;
            }
        }
        return vid;
    }

    @Override
    public String text() {
        if (text == null) {
            try {
                if (node.isEditable()) {
                    text = "";
                } else {
                    CharSequence value = node.getText();
                    text = value == null ? "" : value.toString();
                }
            } catch (Throwable ignored) {
                text = "";
            }
        }
        return text;
    }

    @Override
    public String desc() {
        if (desc == null) {
            try {
                CharSequence value = node.getContentDescription();
                desc = value == null ? "" : value.toString();
            } catch (Throwable ignored) {
                desc = "";
            }
        }
        return desc;
    }

    @Override
    public boolean clickable() {
        if (!clickableLoaded) {
            clickable = bool(node::isClickable);
            clickableLoaded = true;
        }
        return clickable;
    }

    @Override
    public boolean focusable() {
        if (!focusableLoaded) {
            focusable = bool(node::isFocusable);
            focusableLoaded = true;
        }
        return focusable;
    }

    @Override
    public boolean checkable() {
        if (!checkableLoaded) {
            checkable = bool(node::isCheckable);
            checkableLoaded = true;
        }
        return checkable;
    }

    @Override
    public boolean checked() {
        if (!checkedLoaded) {
            checked = bool(node::isChecked);
            checkedLoaded = true;
        }
        return checked;
    }

    @Override
    public boolean editable() {
        if (!editableLoaded) {
            editable = bool(node::isEditable);
            editableLoaded = true;
        }
        return editable;
    }

    @Override
    public boolean longClickable() {
        if (!longClickableLoaded) {
            longClickable = bool(node::isLongClickable);
            longClickableLoaded = true;
        }
        return longClickable;
    }

    @Override
    public boolean visibleToUser() {
        if (!visibleLoaded) {
            visibleToUser = bool(node::isVisibleToUser);
            visibleLoaded = true;
        }
        return visibleToUser;
    }

    @Override
    public int left() {
        return bounds().left;
    }

    @Override
    public int top() {
        return bounds().top;
    }

    @Override
    public int right() {
        return bounds().right;
    }

    @Override
    public int bottom() {
        return bounds().bottom;
    }

    @Override
    public int width() {
        return bounds().width();
    }

    @Override
    public int height() {
        return bounds().height();
    }

    @Override
    public int childCount() {
        if (childCount < 0) {
            try {
                childCount = node.getChildCount();
            } catch (Throwable ignored) {
                childCount = 0;
            }
        }
        return childCount;
    }

    @Override
    public int index() {
        if (index < 0) {
            index = 0;
            try {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) {
                    int count = parent.getChildCount();
                    for (int i = 0; i < count; i++) {
                        AccessibilityNodeInfo child = parent.getChild(i);
                        if (child != null && child.equals(node)) {
                            index = i;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                index = 0;
            }
        }
        return index;
    }

    @Override
    public int depth() {
        if (depth < 0) {
            int result = 0;
            AccessibilityNodeInfo current = node;
            for (int i = 0; current != null && i < 80; i++) {
                try {
                    current = current.getParent();
                } catch (Throwable ignored) {
                    break;
                }
                if (current != null) {
                    result++;
                }
            }
            depth = result;
        }
        return depth;
    }

    @Override
    public SelectorEngine.Node parent() {
        try {
            AccessibilityNodeInfo parent = node.getParent();
            return parent == null ? null : wrap(parent);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public SelectorEngine.Node child(int index) {
        try {
            AccessibilityNodeInfo child = node.getChild(index);
            return child == null ? null : wrap(child);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private AccessibilitySelectorNode wrap(AccessibilityNodeInfo value) {
        AccessibilitySelectorNode cached = cache.get(value);
        if (cached != null) {
            return cached;
        }
        AccessibilitySelectorNode created = new AccessibilitySelectorNode(value, cache);
        cache.put(value, created);
        return created;
    }

    private Rect bounds() {
        if (bounds == null) {
            Rect result = new Rect();
            try {
                node.getBoundsInScreen(result);
            } catch (Throwable ignored) {
                // keep the empty rect
            }
            bounds = result;
        }
        return bounds;
    }

    private static boolean bool(BooleanSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private interface BooleanSupplier {
        boolean get();
    }
}