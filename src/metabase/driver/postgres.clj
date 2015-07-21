(ns metabase.driver.postgres
  (:require [clojure.tools.logging :as log]
            (clojure [set :refer [rename-keys]]
                     [string :as s])
            [korma.db :as kdb]
            [swiss.arrows :refer :all]
            [metabase.driver :as driver]
            [metabase.driver.generic-sql :as generic-sql]
            [metabase.driver.generic-sql.interface :refer :all]))

(def ^:private ^:const column->base-type
  "Map of Postgres column types -> Field base types.
   Add more mappings here as you come across them."
  {:bigint        :BigIntegerField
   :bigserial     :BigIntegerField
   :bit           :UnknownField
   :bool          :BooleanField
   :boolean       :BooleanField
   :box           :UnknownField
   :bpchar        :CharField        ; "blank-padded char" is the internal name of "character"
   :bytea         :UnknownField     ; byte array
   :cidr          :TextField        ; IPv4/IPv6 network address
   :circle        :UnknownField
   :date          :DateField
   :decimal       :DecimalField
   :float4        :FloatField
   :float8        :FloatField
   :geometry      :UnknownField
   :inet          :TextField        ; This was `GenericIPAddressField` in some places in the Django code but not others ...
   :int           :IntegerField
   :int2          :IntegerField
   :int4          :IntegerField
   :int8          :BigIntegerField
   :interval      :UnknownField     ; time span
   :json          :TextField
   :jsonb         :TextField
   :line          :UnknownField
   :lseg          :UnknownField
   :macaddr       :TextField
   :money         :DecimalField
   :numeric       :DecimalField
   :path          :UnknownField
   :pg_lsn        :IntegerField     ; PG Log Sequence #
   :point         :UnknownField
   :real          :FloatField
   :serial        :IntegerField
   :serial2       :IntegerField
   :serial4       :IntegerField
   :serial8       :BigIntegerField
   :smallint      :IntegerField
   :smallserial   :IntegerField
   :text          :TextField
   :time          :TimeField
   :timetz        :TimeField
   :timestamp     :DateTimeField
   :timestamptz   :DateTimeField
   :tsquery       :UnknownField
   :tsvector      :UnknownField
   :txid_snapshot :UnknownField
   :uuid          :UnknownField
   :varbit        :UnknownField
   :varchar       :TextField
   :xml           :TextField
   (keyword "bit varying")                :UnknownField
   (keyword "character varying")          :TextField
   (keyword "double precision")           :FloatField
   (keyword "time with time zone")        :TimeField
   (keyword "time without time zone")     :TimeField
   (keyword "timestamp with timezone")    :DateTimeField
   (keyword "timestamp without timezone") :DateTimeField})

(def ^:private ^:const ssl-params
  "Params to include in the JDBC connection spec for an SSL connection."
  {:ssl        true
   :sslmode    "require"
   :sslfactory "org.postgresql.ssl.NonValidatingFactory"})  ; HACK Why enable SSL if we disable certificate validation?

(defrecord ^:private PostgresDriver []
  ISqlDriverDatabaseSpecific
  (connection-details->connection-spec [_ {:keys [ssl] :as details-map}]
    (-> details-map
        (dissoc :ssl)              ; remove :ssl in case it's false; DB will still try (& fail) to connect if the key is there
        (merge (when ssl           ; merging ssl-params will add :ssl back in if desirable
                 ssl-params))
        (rename-keys {:dbname :db})
        kdb/postgres))

  (database->connection-details [_ {:keys [details]}]
    (let [{:keys [host port]} details]
      (-> details
          (assoc :host host
                 :ssl  (:ssl details)
                 :port (if (string? port) (Integer/parseInt port)
                           port))
          (rename-keys {:dbname :db}))))

  (cast-timestamp-to-date [_ table-name field-name seconds-or-milliseconds]
    (format "(TIMESTAMP WITH TIME ZONE 'epoch' + (\"%s\".\"%s\" * INTERVAL '1 %s'))::date" table-name field-name
            (case           seconds-or-milliseconds
              :seconds      "second"
              :milliseconds "millisecond")))

  (timezone->set-timezone-sql [_ timezone]
    (format "SET LOCAL timezone TO '%s';" timezone)))

(generic-sql/extend-add-generic-sql-mixins PostgresDriver)

(def ^:const driver
  (map->PostgresDriver {:column->base-type    column->base-type
                        :features             (conj generic-sql/features :set-timezone)
                        :sql-string-length-fn :CHAR_LENGTH}))
