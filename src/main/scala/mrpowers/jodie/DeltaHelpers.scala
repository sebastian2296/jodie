package mrpowers.jodie

import org.apache.spark.sql.SparkSession
import io.delta.tables._
import org.apache.spark.sql.expressions.Window.partitionBy
import org.apache.spark.sql.functions.{col, count, row_number}

object DeltaHelpers {

  /**
   * Gets the latest version of a Delta lake
   */
  def latestVersion(path: String): Long = {
    DeltaTable
      .forPath(SparkSession.active, path)
      .history(1)
      .select("version")
      .head()(0)
      .asInstanceOf[Long]
  }

  /**
   * This function remove all duplicate records from a delta table.
   * Duplicate records means all rows that have more than one occurrence
   * of the value of the columns provided in the input parameter duplicationColumns.
   *
   * @param deltaTable: delta table object
   * @param duplicateColumns: collection of columns names that represent the duplication key.
   */
  def removeDuplicateRecords(deltaTable: DeltaTable, duplicateColumns: Seq[String] ): Unit ={
    val df = deltaTable.toDF

    //1 Validate duplicateColumns is not empty
    if(duplicateColumns.isEmpty)
      throw new NoSuchElementException("the input parameter duplicateColumns must not be empty")

    //2 Validate duplicateColumns exists in the delta table.
    JodieValidator.validateColumnsExistsInDataFrame(duplicateColumns,df)

    //3 execute query statement with windows function that will help you identify duplicated records.
    val duplicatedRecords = df
      .withColumn("quantity",count("*").over(partitionBy(duplicateColumns.map(c => col(c)):_*)))
      .filter("quantity>1")
      .drop("quantity")
      .distinct()

    //4 execute delete statement to remove duplicate records from the delta table.
    val deleteCondition = duplicateColumns.map(dc=> s"old.$dc = new.$dc").mkString(" AND ")
    deltaTable.alias("old")
      .merge(duplicatedRecords.alias("new"),deleteCondition)
      .whenMatched()
      .delete()
      .execute()
  }

  /**
   * This function remove duplicate records from a delta table keeping
   * only one occurrence of the deleted record. If not duplicate columns are
   * provided them the primary key is used as partition key to identify duplication.
   * @param deltaTable: delta table object
   * @param duplicateColumns: collection of columns names that represent the duplication key.
   * @param primaryKey: name of the primary key column associated to the delta table.
   */
  def removeDuplicateRecords(deltaTable: DeltaTable, primaryKey: String,duplicateColumns: Seq[String] = Nil): Unit = {
    val df = deltaTable.toDF
    // 1 Validate primaryKey is not empty
    if(primaryKey.isEmpty){
      throw new NoSuchElementException("the input parameter primaryKey must not be empty")
    }

    // 2 Validate if duplicateColumns is not empty that all its columns are in the delta table
    JodieValidator.validateColumnsExistsInDataFrame(duplicateColumns,df)

    // 3 execute query using window function to find duplicate records. Create a match expression to evaluate
    // the case when duplicateColumns is empty and when it is not empty
    val duplicateRecords = duplicateColumns match {
      case Nil =>
        df.withColumn("row_number",
          row_number().over(partitionBy(primaryKey).orderBy(primaryKey)))
          .filter("row_number>1")
          .drop("row_number")
          .distinct()

      case columns =>
        df.withColumn("row_number",
          row_number().over(partitionBy(columns.map(c=>col(c)):_*).orderBy(primaryKey)))
        .filter("row_number>1")
        .drop("row_number")
        .distinct()
    }

    // 4 execute delete statement  in the delta table
    val deleteCondition = (Seq(primaryKey) ++ duplicateColumns).map(c => s"old.$c = new.$c").mkString(" AND ")
    deltaTable.alias("old")
      .merge(duplicateRecords.as("new"),deleteCondition)
      .whenMatched()
      .delete()
      .execute()
  }

}