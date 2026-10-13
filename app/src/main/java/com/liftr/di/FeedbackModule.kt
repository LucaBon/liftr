package com.liftr.di

import com.liftr.domain.feedback.IFeedbackEmitter
import com.liftr.feedback.FeedbackManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackModule {

    @Binds
    @Singleton
    abstract fun bindFeedbackEmitter(impl: FeedbackManager): IFeedbackEmitter
}
