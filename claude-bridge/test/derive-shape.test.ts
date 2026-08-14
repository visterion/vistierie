import { describe, it, expect } from "vitest";

import { deriveShape } from "../src/complete.js";

describe("deriveShape", () => {
  it("types a nested array-of-objects property and rejects the stringified-JSON form", () => {
    const shape = deriveShape({
      type: "object",
      properties: {
        items: {
          type: "array",
          items: {
            type: "object",
            properties: {
              code: { type: "string" },
              score: { type: "number" },
            },
          },
        },
      },
    });

    const items = shape.items;
    expect(items).toBeDefined();

    // The real, structured form must pass ...
    expect(items.safeParse([{ code: "AAA", score: 0.5 }]).success).toBe(true);
    // ... and the JSON-encoded string form that broke production must not.
    expect(items.safeParse('[{"code":"AAA","score":0.5}]').success).toBe(false);
    // A non-object array element is rejected too.
    expect(items.safeParse(["AAA"]).success).toBe(false);
    // Element fields keep their declared type.
    expect(items.safeParse([{ code: 7 }]).success).toBe(false);
    // Unknown element keys stay permissive (Vistierie owns real validation).
    expect(items.safeParse([{ code: "AAA", extra: true }]).success).toBe(true);
  });

  it("keeps every top-level property optional", () => {
    const shape = deriveShape({
      type: "object",
      properties: { name: { type: "string" } },
      required: ["name"],
    });

    expect(shape.name.safeParse(undefined).success).toBe(true);
    expect(shape.name.safeParse("x").success).toBe(true);
    expect(shape.name.safeParse(3).success).toBe(false);
  });

  it("accepts a listed enum value and rejects an unlisted one", () => {
    const shape = deriveShape({
      type: "object",
      properties: { mode: { type: "string", enum: ["fast", "slow"] } },
    });

    expect(shape.mode.safeParse("fast").success).toBe(true);
    expect(shape.mode.safeParse("slow").success).toBe(true);
    expect(shape.mode.safeParse("sideways").success).toBe(false);
  });

  it("types scalars", () => {
    const shape = deriveShape({
      type: "object",
      properties: {
        s: { type: "string" },
        n: { type: "number" },
        i: { type: "integer" },
        b: { type: "boolean" },
        z: { type: "null" },
      },
    });

    expect(shape.s.safeParse("x").success).toBe(true);
    expect(shape.s.safeParse(1).success).toBe(false);
    expect(shape.n.safeParse(1.5).success).toBe(true);
    expect(shape.n.safeParse("1.5").success).toBe(false);
    expect(shape.i.safeParse(2).success).toBe(true);
    expect(shape.i.safeParse(2.5).success).toBe(false);
    expect(shape.b.safeParse(true).success).toBe(true);
    expect(shape.b.safeParse("true").success).toBe(false);
    expect(shape.z.safeParse(null).success).toBe(true);
    expect(shape.z.safeParse(0).success).toBe(false);
  });

  it("types a nested object property and leaves its members optional", () => {
    const shape = deriveShape({
      type: "object",
      properties: {
        window: {
          type: "object",
          properties: { from: { type: "string" }, days: { type: "integer" } },
          required: ["from"],
        },
      },
    });

    expect(shape.window.safeParse({ from: "a", days: 2 }).success).toBe(true);
    expect(shape.window.safeParse({}).success).toBe(true);
    expect(shape.window.safeParse({ days: 2.5 }).success).toBe(false);
    expect(shape.window.safeParse("from=a").success).toBe(false);
  });

  it("falls back to an any-shape for an unknown or absent type", () => {
    const shape = deriveShape({
      type: "object",
      properties: {
        weird: { type: "quaternion" },
        untyped: { description: "no type at all" },
        empty: {},
      },
    });

    for (const key of ["weird", "untyped", "empty"]) {
      expect(shape[key].safeParse("anything").success).toBe(true);
      expect(shape[key].safeParse({ a: [1] }).success).toBe(true);
      expect(shape[key].safeParse(undefined).success).toBe(true);
    }
  });

  it("stays permissive where the item or property schema is not usable", () => {
    const shape = deriveShape({
      type: "object",
      properties: {
        loose: { type: "array" },
        bad: { type: "array", items: "not-a-schema" },
        bag: { type: "object" },
        broken: "not-a-schema",
      },
    });

    expect(shape.loose.safeParse([1, "a", {}]).success).toBe(true);
    expect(shape.loose.safeParse("nope").success).toBe(false);
    expect(shape.bad.safeParse([{ any: 1 }]).success).toBe(true);
    expect(shape.bag.safeParse({ a: 1 }).success).toBe(true);
    expect(shape.broken.safeParse("anything").success).toBe(true);
  });

  it("does not throw on a malformed input schema", () => {
    expect(deriveShape(undefined)).toEqual({});
    expect(deriveShape(null)).toEqual({});
    expect(deriveShape("a string")).toEqual({});
    expect(deriveShape(42)).toEqual({});
    expect(deriveShape({})).toEqual({});
    expect(deriveShape({ properties: null })).toEqual({});
    expect(deriveShape({ properties: "nope" })).toEqual({});
    expect(deriveShape({ properties: [] })).toEqual({});
    expect(deriveShape({ properties: {} })).toEqual({});
  });
});
