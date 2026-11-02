package com.instagram.feed.media;

import java.util.ArrayList;
import java.util.List;

/** Test double with the same package/class name as Instagram's Media. */
public class Media {
    public Object A00;
    public List<Object> A01 = new ArrayList<>();
    public String adAction;
    public List<Object> adMeta = new ArrayList<>();

    public String A72() { return adAction; }
    public List<Object> A8b() { return adMeta; }
}
