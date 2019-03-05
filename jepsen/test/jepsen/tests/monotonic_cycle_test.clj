(ns jepsen.tests.monotonic-cycle-test
  (:require [jepsen.tests.monotonic-cycle :refer :all]
            [jepsen.checker :as checker]
            [jepsen.util :refer [map-vals]]
            [knossos.op :as op]
            [clojure.test :refer :all]))

(deftest tarjan-test
  (testing "Can analyze keyword graphs"
    ;; Modeled after the example on the wikipedia page
    (let [graph {:a #{:b}    :b #{:c}
                 :c #{:a}    :d #{:b :c :e}
                 :e #{:d :f} :f #{:c :g}
                 :g #{:f}    :h #{:f :h}}]
      (is (= (tarjan graph)
              #{#{:c :b :a} #{:g :f} #{:e :d} #{:h}}))))

  (testing "Can analyze integer graphs"
    (let [graph {1 #{2}   2 #{3}
                 3 #{1}   4 #{2 3 5}
                 5 #{4 6} 6 #{3 7}
                 7 #{6}   8 #{7 8}}]
      (is (= (tarjan graph)
             #{#{3 2 1} #{6 7} #{5 4} #{8}}))))

  (testing "Can detect when the whole graph is strongly connected"
    (let [graph {1 #{2} 2 #{3}
                 3 #{4} 4 #{5}
                 5 #{6} 6 #{7}
                 7 #{8} 8 #{1}}]
      (is (= (tarjan graph)
             #{#{1 2 3 4 5 6 7 8}}))))

  (testing "Can verify that no SCCs exist"
    ;; A node with no strong connections is returned as a single-element set
    (let [graph {1 #{} 2 #{} 3 #{} 4 #{}
                 5 #{} 6 #{} 7 #{} 8 #{}}]
      (is (= (tarjan graph)
             #{#{1} #{2} #{3} #{4}
               #{5} #{6} #{7} #{8}})))

    ;; Self connections are equivalent to no connections in this impl.
    (let [graph {1 #{1} 2 #{2} 3 #{3} 4 #{4}}]
      (is (= (tarjan graph)
             #{#{1} #{2} #{3} #{4}})))))

(deftest graph-test
  (testing "Can render a linear partial order"
    (let [history [(op/ok 0 :read {:x 0})
                   (op/ok 0 :read {:x 1})]]
      (is (= (graph history)
             {:x {0 #{1}
                  1 #{}}}))))

  (testing "Can render a circular partial order"
    (let [history [(op/ok 0 :read {:x 0})
                   (op/ok 0 :read {:x 1})
                   (op/ok 0 :read {:x 0})]]
      (is (= (graph history)
             {:x {0 #{1}
                  1 #{0}}}))))

  (testing "Ignores duplicate sequential reads"
    (let [history [(op/ok 0 :read {:x 0})
                   (op/ok 0 :read {:x 1})
                   (op/ok 0 :read {:x 1})
                   (op/ok 0 :read {:x 1})]]
      (is (= (graph history)
             {:x {0 #{1}
                  1 #{}}}))))

  (testing "Can render graphs for multiple registers"
    (let [history [(op/ok 0 :read {:x 0 :y 1})
                   (op/ok 0 :read {:x 1 :y 1})
                   (op/ok 0 :read {:x 1 :y 2})
                   (op/ok 0 :read {:x 2 :y 2})]]
      (is (= (graph history)
             {:x {0 #{1}
                  1 #{2}
                  2 #{}}
              :y {1 #{2}
                  2 #{}}})))))

(deftest errors-test
  (testing "Flags lööps"
    (let [graph {:x {1 #{2}
                     2 #{1}}}
          components (map-vals tarjan graph)
          e          (map-vals errors components)]
      (is (= e
             {:x {#{1 2} true}}))))

  (testing "Doesn't flag lööpless"
    (let [graph {:x {1 #{1}
                     2 #{1}}}
          components (map-vals tarjan graph)
          e          (map-vals errors components)]
      (is (= e {:x nil}))))

  (testing "Flags multiple lööps"
    (let [graph {:x {1 #{2}
                     2 #{1}}
                 :y {3 #{4}
                     4 #{3}}}
          components (map-vals tarjan graph)
          e          (map-vals errors components)]
      (is (= e {:x {#{1 2} true}
                :y {#{3 4} true}}))))

  (testing "Flags mixed lööps and lööpless"
    (let [graph {:x {1 #{2}
                     2 #{1}
                     3 #{4}
                     4 #{}
                     5 #{}}
                 :y {0 #{1}
                     1 #{2}
                     2 #{}}}
          components (map-vals tarjan graph)
          e          (map-vals errors components)]
      (is (= e {:x {#{1 2} true}
                :y nil}))))

  (testing "Doesn't flag multiple lööpless"
    (let [graph {:x {1 #{1}
                     2 #{1}}
                 :y {0 #{1}
                     1 #{}}}
          components (map-vals tarjan graph)
          e          (map-vals errors components)]
      (is (= e {:x nil
                :y nil})))))

(defn big-history-gen
  [v]
  (let [f    (rand-nth [:write :read])
        proc (rand-int 100)
        k    (rand-nth [:x :y])
        type (rand-nth [:ok :ok :ok :ok :ok
                        :fail :info :info])]
    [{:process proc, :type :invoke, :f f, :value {k v}}
     {:process proc, :type type,    :f f, :value {k v}}]))

(deftest checker-test
  (testing "Can validate histories"
    (let [checker (checker)
          history [(op/invoke 0 :read nil)
                   (op/ok     0 :read {:x 0 :y 0})
                   (op/invoke 0 :write {:x 1})
                   (op/ok     0 :write {:y 1})
                   (op/invoke 0 :read nil)
                   (op/ok     0 :read {:x 1 :y 1})]
          r (checker/check checker nil history nil)]
      (is (:valid? r))))

  (testing "Can invalidate histories"
    (let [checker (checker)
          history [(op/invoke 0 :read nil)
                   (op/ok     0 :read {:x 0 :y 0})
                   (op/invoke 0 :read nil)
                   (op/ok     0 :read {:x 1 :y 1})
                   (op/invoke 0 :read nil)
                   (op/ok     0 :read {:x 0 :y 1})]
          r (checker/check checker nil history nil)]
      (is (not (:valid? r)))))

  (testing "Auto-validates single-key histories"
    (let [checker (checker)
          history [(op/invoke 0 :read nil)
                   (op/ok     0 :read 0)
                   (op/invoke 0 :read nil)
                   (op/ok     0 :read 1)
                   (op/invoke 0 :read nil)
                   (op/ok     0 :read 0)]
          r (checker/check checker nil history nil)]
      (is (:valid? r))))

  (testing "Can handle large histories"
    (let [checker (checker)
          history (->> (range)
                       (mapcat big-history-gen)
                       (take 10000)
                       vec)
          r (checker/check checker nil history nil)]
      (is (:valid? r)))))

(defn read-only-gen
  [v]
  (let [proc (rand-int 100)]
    [{:process proc, :type :invoke, :f :read, :value v}
     {:process proc, :type :ok,     :f :read, :value v}]))

;; FIXME Test this on tarjan's directly
#_(deftest ^:integration stackoverflow-test
  (testing "just inducing the depth limit problem"
    (let [checker (checker)
          history (->> (range)
                       (mapcat big-history-gen)
                       (take 5000)
                       vec)]
      (time
       (dotimes [n 100]
         (checker/check checker nil history nil)))
      (is (:valid? r)))))
