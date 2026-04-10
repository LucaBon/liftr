package com.lifthelper.di

import android.content.Context
import androidx.room.Room
import com.lifthelper.data.local.database.LiftDatabase
import com.lifthelper.data.local.database.dao.RepDao
import com.lifthelper.data.local.database.dao.SetDao
import com.lifthelper.data.local.database.dao.WorkoutDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LiftDatabase {
        return Room.databaseBuilder(
            context,
            LiftDatabase::class.java,
            "lift_helper_db"
        )
            .addMigrations(LiftDatabase.MIGRATION_1_2, LiftDatabase.MIGRATION_2_3)
            .build()
    }

    @Provides
    fun provideWorkoutDao(database: LiftDatabase): WorkoutDao {
        return database.workoutDao()
    }

    @Provides
    fun provideSetDao(database: LiftDatabase): SetDao {
        return database.setDao()
    }

    @Provides
    fun provideRepDao(database: LiftDatabase): RepDao {
        return database.repDao()
    }
}
