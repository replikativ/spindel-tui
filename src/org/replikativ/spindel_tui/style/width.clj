(ns org.replikativ.spindel-tui.style.width
  "Text width calculation for terminal display.

   Handles:
   - ANSI escape sequences (zero width)
   - Wide characters (CJK, emojis = 2 cells)
   - Combining characters (zero width)"
  (:import [org.jline.utils AttributedString]))

(defn strip-ansi
  "Remove ANSI escape sequences from a string."
  [s]
  (if (nil? s)
    ""
    (.toString (AttributedString/fromAnsi s))))

;; JLine's wcwidth (via columnLength) undercounts BMP code points that have
;; default *emoji presentation* (Unicode Emoji_Presentation=Yes) — e.g. ❌
;; U+274C, ✅ U+2705 — as 1 cell, while emoji-capable terminals render them as
;; 2. A line full of such glyphs (a model's ❌/✅ table) then gets padded a cell
;; short per glyph, overflows the box, and corrupts the rows below. We add a
;; conservative +1 correction for each such code point (and for VARIATION
;; SELECTOR-16, which forces emoji presentation on the preceding char).
;; Over-counting is safe (a row ends a hair short, never overflows).
(def ^:private emoji-presentation-ranges
  [[0x231A 0x231B] [0x23E9 0x23EC] [0x23F0 0x23F0] [0x23F3 0x23F3]
   [0x25FD 0x25FE] [0x2614 0x2615] [0x2648 0x2653] [0x267F 0x267F]
   [0x2693 0x2693] [0x26A1 0x26A1] [0x26AA 0x26AB] [0x26BD 0x26BE]
   [0x26C4 0x26C5] [0x26CE 0x26CE] [0x26D4 0x26D4] [0x26EA 0x26EA]
   [0x26F2 0x26F3] [0x26F5 0x26F5] [0x26FA 0x26FA] [0x26FD 0x26FD]
   [0x2705 0x2705] [0x270A 0x270B] [0x2728 0x2728] [0x274C 0x274C]
   [0x274E 0x274E] [0x2753 0x2755] [0x2757 0x2757] [0x2795 0x2797]
   [0x27B0 0x27B0] [0x27BF 0x27BF] [0x2B1B 0x2B1C] [0x2B50 0x2B50]
   [0x2B55 0x2B55]])

(defn- wide-emoji-cp?
  [cp]
  (or (= cp 0xFE0F)
      (some (fn [[lo hi]] (and (>= cp lo) (<= cp hi))) emoji-presentation-ranges)))

(defn- emoji-correction
  "Cells JLine undercounts for default-emoji-presentation glyphs in `s`."
  [^String s]
  (loop [i 0 n 0]
    (if (< i (.length s))
      (let [cp (.codePointAt s i)]
        (recur (+ i (Character/charCount cp))
               (if (wide-emoji-cp? cp) (inc n) n)))
      n)))

(defn string-width
  "Measure the display width of a string in terminal cells.

   - ANSI escape sequences have zero width
   - Wide characters (CJK, astral emoji) count as 2 cells
   - BMP emoji-presentation glyphs (❌ ✅ …) count as 2 cells (JLine undercounts)
   - Combining characters count as 0 cells"
  [s]
  (if (or (nil? s) (empty? s))
    0
    (let [stripped (.toString (AttributedString/fromAnsi s))]
      (+ (.columnLength (AttributedString/fromAnsi s))
         (emoji-correction stripped)))))

(defn truncate
  "Truncate a string to fit within a given display width.

   Options:
     :tail - String to append when truncated (default \"...\")"
  [s width & {:keys [tail] :or {tail "..."}}]
  (if (nil? s)
    s
    (let [attr-s (AttributedString/fromAnsi s)]
      (if (<= (.columnLength attr-s) width)
        s
        (let [tail-width (string-width tail)
              target-width (- width tail-width)]
          (if (neg? target-width)
            ""
            (str (.columnSubSequence attr-s 0 target-width) tail)))))))

(defn pad-right
  "Pad a string on the right to reach a target display width."
  [s width & {:keys [char] :or {char \space}}]
  (let [current (string-width s)
        needed (- width current)]
    (if (pos? needed)
      (str s (apply str (repeat needed char)))
      s)))

(defn pad-left
  "Pad a string on the left to reach a target display width."
  [s width & {:keys [char] :or {char \space}}]
  (let [current (string-width s)
        needed (- width current)]
    (if (pos? needed)
      (str (apply str (repeat needed char)) s)
      s)))
