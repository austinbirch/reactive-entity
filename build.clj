(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn read-version-from-package-json
  []
  (let [package-json-path "package.json"
        package-json-content (slurp (io/file package-json-path))
        package-data (json/read-str package-json-content
                                    :key-fn keyword)]
    (:version package-data)))

(def lib 'austinbirch/reactive-entity)
(def class-dir "target/classes")

(defn test
  "Run all the tests."
  [opts]
  (let [basis (b/create-basis {:aliases [:test]})
        cmds (b/java-command
               {:basis basis
                :main 'clojure.main
                :main-args ["-m" "shadow.cljs.devtools.cli" "compile" "test"]})
        {:keys [exit]} (b/process cmds)
        _ (when-not (zero? exit) (throw (ex-info "Failed to build tests" {})))
        {:keys [exit]} (b/process {:command-args ["node" "out/node-tests.js"]})]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- pom-template
  [version]
  [[:url "https://github.com/austinbirch/reactive-entity"]
   [:licenses
    [:license
     [:name "MIT"]
     [:url "https://opensource.org/license/mit"]]]
   [:developers
    [:developer
     [:name "Austin Birch"]]]
   [:scm
    [:url "https://github.com/austinbirch/reactive-entity"]
    [:connection "scm:git:https://github.com/austinbirch/reactive-entity.git"]
    [:developerConnection "scm:git:ssh:git@github.com:austinbirch/reactive-entity.git"]
    [:tag version]]])

(defn- jar-opts
  [opts]
  (let [version (read-version-from-package-json)]
    (assoc opts
      :lib lib
      :version version
      :jar-file (format "target/%s-%s.jar" lib version)
      :basis (b/create-basis {})
      :class-dir class-dir
      :target "target"
      :src-dirs ["main"]
      :pom-data (pom-template version))))

(defn ci
  "Run the CI pipeline of tests (and build the JAR)."
  [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["src/main"]
                 :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn install
  "Install the JAR locally."
  [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy
  "Deploy the JAR to Clojars."
  [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote
                :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)

(defn release
  "Test, build the JAR, install it locally, then deploy the JAR to Clojars"
  [opts]
  (-> (ci opts)
      (install)
      (deploy)))
