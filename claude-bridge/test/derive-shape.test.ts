import { describe, it, expect } from "vitest";
import { z } from "zod";

import { deriveShape } from "../src/complete.js";

/**
 * What the model is actually shown. The Agent SDK converts the derived Zod
 * types with zod's own `toJSONSchema` in input mode, so this is the advertised
 * contract — and the half of the behavior that `.catch()` must not flatten.
 */
function advertised(t: z.ZodTypeAny): Record<string, any> {
  const { $schema, ...rest } = z.toJSONSchema(t, { io: "input" }) as Record<string, any>;
  return rest;
}

/**
 * Parsing must be TOTAL. A rejection happens inside the SDK before the tool
 * handler runs, after the `tool_use` block was already registered as pending —
 * it desynchronises the bridge's FIFO call matcher and makes the model retry,
 * i.e. execute the tool twice.
 */
function parsesAnything(t: z.ZodTypeAny): boolean {
  const junk: unknown[] = [
    undefined,
    null,
    "",
    "nonsense",
    '[{"code":"AAA"}]',
    0,
    2.5,
    -1,
    true,
    [],
    ["AAA"],
    [{ code: 7 }],
    {},
    { a: [1] },
  ];
  return junk.every((v) => t.safeParse(v).success);
}

describe("deriveShape", () => {
  it("never rejects a value, whatever the declared type", () => {
    const shape = deriveShape({
      type: "object",
      properties: {
        items: { type: "array", items: { type: "object", properties: { code: { type: "string" } } } },
        mode: { type: "string", enum: ["fast", "slow"] },
        count: { type: "integer" },
        name: { type: "string" },
        ratio: { type: "number" },
        flag: { type: "boolean" },
        nothing: { type: "null" },
        window: { type: "object", properties: { from: { type: "string" } } },
        note: { type: ["string", "null"] },
      },
    });

    for (const [key, type] of Object.entries(shape)) {
      expect(parsesAnything(type), `property ${key} rejected a value`).toBe(true);
    }
  });

  it("makes the object the SDK actually parses total, except for a non-object", () => {
    // The SDK turns the raw shape into a plain, non-strict `z.object(shape)` and
    // safeParses the tool arguments against it. Mirror that here: `.catch()` on
    // the properties only makes the parse total as long as the object itself
    // stays non-strict and catchall-free. If an SDK bump changed that, the
    // matcher desync would return silently — this test is the canary.
    const args = z.object(
      deriveShape({
        type: "object",
        properties: {
          items: {
            type: "array",
            items: { type: "object", properties: { code: { type: "string" } } },
          },
          count: { type: "integer" },
          mode: { type: "string", enum: ["fast", "slow"] },
        },
        required: ["items", "count"],
      }),
    );

    // A declared-required key may be missing.
    expect(args.safeParse({ mode: "fast" }).success).toBe(true);
    // An unknown key does not make the object reject — the strictness canary.
    expect(args.safeParse({ items: [], unknown_key: 1, nested: { a: [1] } }).success).toBe(true);
    // Violating values reject nowhere, through the object rather than the property:
    // the stringified array that broke production, a fractional integer, an
    // off-enum member, and a wholly wrong type.
    expect(args.safeParse({ items: '[{"code":"AAA"}]' }).success).toBe(true);
    expect(args.safeParse({ count: 2.5 }).success).toBe(true);
    expect(args.safeParse({ mode: "sideways" }).success).toBe(true);
    expect(args.safeParse({ items: 7, count: "many", mode: null }).success).toBe(true);
    expect(args.safeParse({}).success).toBe(true);

    // The documented boundary: absent arguments are not an object, and no
    // property-level `.catch()` can rescue that. This is the one remaining
    // trigger of the FIFO-matcher desync, deliberately left to the deferred
    // matcher-hardening ticket rather than papered over here.
    expect(args.safeParse(undefined).success).toBe(false);
  });

  it("advertises a nested array-of-objects while accepting the stringified form", () => {
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

    // The advertised structure is what the model cannot guess: production once
    // sent this property as a JSON-encoded string. `.catch()` must not flatten it.
    expect(advertised(shape.items)).toEqual({
      type: "array",
      items: {
        type: "object",
        properties: { code: { type: "string" }, score: { type: "number" } },
        additionalProperties: {},
      },
    });

    expect(shape.items.safeParse([{ code: "AAA", score: 0.5 }]).success).toBe(true);
    // ... and the JSON-encoded string form that broke production is tolerated
    // rather than rejected, because rejecting it is the worse failure.
    expect(shape.items.safeParse('[{"code":"AAA","score":0.5}]').success).toBe(true);
  });

  it("keeps every top-level property optional", () => {
    const shape = deriveShape({
      type: "object",
      properties: { name: { type: "string" } },
      required: ["name"],
    });

    expect(advertised(shape.name)).toEqual({ type: "string" });
    expect(shape.name.safeParse(undefined).success).toBe(true);
    expect(shape.name.safeParse("x").success).toBe(true);
  });

  it("advertises an enum by its members, not merely by their type", () => {
    const shape = deriveShape({
      type: "object",
      properties: {
        mode: { type: "string", enum: ["fast", "slow"] },
        level: { enum: [1, 2, 3] },
      },
    });

    expect(advertised(shape.mode)).toEqual({
      anyOf: [
        { type: "string", const: "fast" },
        { type: "string", const: "slow" },
      ],
    });
    expect(advertised(shape.level)).toEqual({
      anyOf: [
        { type: "number", const: 1 },
        { type: "number", const: 2 },
        { type: "number", const: 3 },
      ],
    });

    expect(shape.mode.safeParse("fast").success).toBe(true);
    // An off-enum value is still ACCEPTED — the constraint informs the model, it
    // does not gate the call. Vistierie is the authoritative validator.
    expect(shape.mode.safeParse("sideways").success).toBe(true);
    expect(shape.level.safeParse(99).success).toBe(true);
  });

  it("advertises null among an enum's members when it lists null", () => {
    const shape = deriveShape({
      type: "object",
      properties: { horizon: { type: ["string", "null"], enum: ["short", "long", null] } },
    });

    expect(advertised(shape.horizon)).toEqual({
      anyOf: [
        { type: "string", const: "short" },
        { type: "string", const: "long" },
        { type: "null" },
      ],
    });
    expect(shape.horizon.safeParse(null).success).toBe(true);
    expect(shape.horizon.safeParse("short").success).toBe(true);
  });

  it("advertises both members of a nullable type union", () => {
    const shape = deriveShape({
      type: "object",
      properties: { note: { type: ["string", "null"] } },
    });

    expect(advertised(shape.note)).toEqual({
      anyOf: [{ type: "string" }, { type: "null" }],
    });
  });

  it("falls back to any for a mixed-type enum", () => {
    const shape = deriveShape({
      type: "object",
      properties: { mixed: { enum: ["a", 1] }, objects: { enum: [{ a: 1 }] } },
    });

    expect(advertised(shape.mixed)).toEqual({});
    expect(advertised(shape.objects)).toEqual({});
  });

  it("advertises scalars, integer included", () => {
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

    expect(advertised(shape.s)).toEqual({ type: "string" });
    expect(advertised(shape.n)).toEqual({ type: "number" });
    // `integer`, not a plain number: the constraint is advertised again now that
    // it can no longer cause a rejection.
    expect(advertised(shape.i)).toMatchObject({ type: "integer" });
    expect(advertised(shape.b)).toEqual({ type: "boolean" });
    expect(advertised(shape.z)).toEqual({ type: "null" });

    // A fractional value still reaches the handler.
    expect(shape.i.safeParse(2.5).success).toBe(true);
  });

  it("advertises a nested object property with optional members", () => {
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

    const schema = advertised(shape.window);
    expect(schema.type).toBe("object");
    expect(schema.required).toBeUndefined();
    expect(schema.properties.from).toEqual({ type: "string" });
    // The nested constraint survives too — `.catch()` at the top level must not
    // reach down and erase it.
    expect(schema.properties.days).toMatchObject({ type: "integer" });

    expect(shape.window.safeParse({ from: "a", days: 2 }).success).toBe(true);
    expect(shape.window.safeParse("from=a").success).toBe(true);
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
      expect(advertised(shape[key])).toEqual({});
      expect(parsesAnything(shape[key])).toBe(true);
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

    expect(advertised(shape.loose)).toEqual({ type: "array", items: {} });
    expect(advertised(shape.bad)).toEqual({ type: "array", items: {} });
    expect(advertised(shape.bag)).toMatchObject({ type: "object" });
    expect(advertised(shape.broken)).toEqual({});
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
