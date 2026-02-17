(ns is.simm.spindel-tui.style.width
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

(defn string-width
  "Measure the display width of a string in terminal cells.

   - ANSI escape sequences have zero width
   - Wide characters (CJK, emojis) count as 2 cells
   - Combining characters count as 0 cells"
  [s]
  (if (or (nil? s) (empty? s))
    0
    (.columnLength (AttributedString/fromAnsi s))))

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
