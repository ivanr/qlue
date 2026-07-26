package com.webkreator.qlue.view.canoe.corpus;

import java.util.Objects;

/**
 * One attacker-controlled value, together with the family it belongs to and a note saying what it is
 * trying to reach.
 *
 * <p>Cases declare intent — "this template, attacked with a JS-URL payload" — rather than pasting
 * strings, so that adding a variant to a family strengthens every case that uses it.
 *
 * <p>The identifier is an explicit slug rather than a hash of the value. Payload values are full of
 * quotes, NULs and lone surrogates, none of which survive a JUnit display name or a report anchor
 * intact, and a hash would collide silently once a family grows past a few dozen entries — Appendix
 * A section A.4 alone projects around 45 URL-prefix variants. {@link Payloads} asserts slug
 * uniqueness at registration.
 */
public final class Payload {

    private final String family;
    private final String slug;
    private final String value;
    private final String reaches;

    Payload(String family, String slug, String value, String reaches) {
        this.family = Objects.requireNonNull(family, "family");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.value = Objects.requireNonNull(value, "value");
        this.reaches = Objects.requireNonNull(reaches, "reaches");
    }

    /** The family name, e.g. {@code JS_URL}. Used to group cases and to filter corpus runs. */
    public String family() {
        return family;
    }

    /** The within-family identifier, e.g. {@code tab-split}. */
    public String slug() {
        return slug;
    }

    /** The value bound to the template's reference. */
    public String value() {
        return value;
    }

    /** What this payload is trying to reach, for failure messages and the generated report. */
    public String reaches() {
        return reaches;
    }

    /** A stable, unique, human-readable identifier: {@code JS_URL/tab-split}. */
    public String id() {
        return family + "/" + slug;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Payload)) {
            return false;
        }
        Payload other = (Payload) o;
        return family.equals(other.family) && slug.equals(other.slug);
    }

    @Override
    public int hashCode() {
        return Objects.hash(family, slug);
    }

    @Override
    public String toString() {
        return id();
    }
}
